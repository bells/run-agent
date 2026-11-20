package cn.watsonzhu.runagent.controller;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.annotation.JsonInclude;
import cn.watsonzhu.runagent.agent.RunAgentService;
import cn.watsonzhu.runagent.evidence.AdviceContext;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService.Bundle;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService.Evidence;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService.EvidenceStatus;
import cn.watsonzhu.runagent.exception.InvalidRunningQueryException;
import cn.watsonzhu.runagent.exception.RunningDataUnavailableException;
import cn.watsonzhu.runagent.mcp.McpRunningStatistics;
import cn.watsonzhu.runagent.model.running.DistanceType;
import cn.watsonzhu.runagent.service.RunningDateRange;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

import static cn.watsonzhu.runagent.controller.PrivateApiAccess.error;

@RestController
@ConditionalOnProperty(prefix = "run-agent", name = {"private-chat.enabled", "private-training.enabled"}, havingValue = "true")
public class PrivateTrainingController {
    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private static final Duration EVIDENCE_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration RESPONSE_BUDGET = Duration.ofSeconds(18);
    private static final Duration ADVICE_TIMEOUT = Duration.ofSeconds(7);

    private final McpRunningStatistics statistics;
    private final RunningEvidenceService evidence;
    private final RunAgentService agent;
    private final PrivateApiAccess access;
    private final MeterRegistry metrics;
    private final boolean publicEnabled;
    private final int publicAdviceDailyLimit;
    private final Semaphore queries = new Semaphore(2);
    private final Semaphore publicQueries = new Semaphore(1);
    private final Semaphore adviceQueries = new Semaphore(1);
    private final Semaphore publicAdviceQueries = new Semaphore(1);
    private LocalDate publicAdviceDay = LocalDate.now(ZoneOffset.UTC);
    private int publicAdviceCalls;

    public PrivateTrainingController(McpRunningStatistics statistics, RunningEvidenceService evidence, RunAgentService agent,
            MeterRegistry metrics, @Value("${run-agent.private-chat.token:}") String token,
            @Value("${run-agent.public-api.enabled:false}") boolean publicEnabled,
            @Value("${run-agent.public-api.training-advice-daily-limit:12}") int publicAdviceDailyLimit) {
        if (publicAdviceDailyLimit < 0) throw new IllegalArgumentException("Public training advice limit cannot be negative");
        this.statistics = statistics;
        this.evidence = evidence;
        this.agent = agent;
        this.metrics = metrics;
        this.access = new PrivateApiAccess(token);
        this.publicEnabled = publicEnabled;
        this.publicAdviceDailyLimit = publicAdviceDailyLimit;
    }

    @PostMapping(value = "/api/v1/training/stats", consumes = "application/json", produces = "application/json")
    public Mono<ResponseEntity<?>> query(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) Mono<JsonNode> body) {
        String requestId = UUID.randomUUID().toString();
        boolean publicRequest = authorization == null && publicEnabled;
        if (!publicRequest && !access.allows(authorization)) {
            return Mono.just(PrivateApiAccess.unauthorized(requestId));
        }

        return body.map(TrainingRequest::parse)
                .flatMap(request -> execute(requestId, request, publicRequest))
                .switchIfEmpty(Mono.fromSupplier(() -> invalid(requestId)))
                .onErrorResume(ServerWebInputException.class, exception -> Mono.just(invalid(requestId)))
                .onErrorResume(InvalidRunningQueryException.class, exception -> Mono.just(invalid(requestId)));
    }

    private Mono<ResponseEntity<?>> execute(String requestId, TrainingRequest request, boolean publicRequest) {
        var sample = Timer.start(metrics);
        var permits = publicRequest ? publicQueries : queries;
        long deadline = System.nanoTime() + RESPONSE_BUDGET.toNanos();
        return Mono.fromCallable(() -> {
                    if (!permits.tryAcquire()) return null;
                    try {
                        return result(requestId, request.query(), publicRequest);
                    } finally {
                        permits.release();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(TIMEOUT)
                .flatMap(result -> attachEvidence(result, request.advice(), publicRequest, deadline))
                .<ResponseEntity<?>>map(result -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result))
                .switchIfEmpty(Mono.fromSupplier(() -> error(429, requestId, "RATE_LIMITED", "Try again in a few seconds.")))
                .onErrorResume(RunningDataUnavailableException.class, exception -> Mono.just(unavailable(requestId)))
                .onErrorResume(TimeoutException.class, exception -> Mono.just(unavailable(requestId)))
                .doOnNext(response -> sample.stop(metrics.timer("runagent.private.training",
                        "kind", request.query().kind(), "status", Integer.toString(response.getStatusCode().value()))));
    }

    private Mono<TrainingResult> attachEvidence(TrainingResult result, AdviceContext advice,
            boolean publicRequest, long deadline) {
        if (AdviceContext.NONE.equals(advice)) return Mono.just(result);
        var permits = publicRequest ? publicAdviceQueries : adviceQueries;
        String topic = advice.goal() == null ? "跑步训练的跑量、配速和恢复原则" : advice.goal();
        return Mono.fromCallable(() -> {
                    if (!permits.tryAcquire()) return null;
                    try {
                        return evidence.collect(topic, advice);
                    } finally {
                        permits.release();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(EVIDENCE_TIMEOUT)
                .flatMap(bundle -> trainingAdvice(result, bundle, topic, permits, publicRequest, deadline))
                .defaultIfEmpty(result)
                .onErrorReturn(result);
    }

    private Mono<TrainingResult> trainingAdvice(TrainingResult result, Bundle bundle, String topic,
            Semaphore permits, boolean publicRequest, long deadline) {
        Evidence references = bundle.evidence();
        TrainingResult resultWithEvidence = result.withAdvice(references, null);
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0 || (references.knowledge().status() != EvidenceStatus.READY
                && references.weather().status() != EvidenceStatus.READY)) {
            return Mono.just(resultWithEvidence);
        }

        return Mono.fromCallable(() -> {
                    if (!permits.tryAcquire()) return resultWithEvidence;
                    try {
                        if (System.nanoTime() - deadline >= 0 || (publicRequest && !reservePublicAdvice())) {
                            return resultWithEvidence;
                        }
                        // The model receives the stated goal and evidence, never the queried training statistics.
                        String answer = agent.privateChat("根据提供的依据，用两句简体中文给出跑步训练建议。目标：" + topic,
                                bundle.promptContext());
                        return answer == null || answer.isBlank() || !bundle.allowsAnswer(answer) ? resultWithEvidence
                                : result.withAdvice(references, answer.strip());
                    } finally {
                        permits.release();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofNanos(Math.min(ADVICE_TIMEOUT.toNanos(), remainingNanos)))
                .onErrorReturn(resultWithEvidence);
    }

    private synchronized boolean reservePublicAdvice() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!publicAdviceDay.equals(today)) {
            publicAdviceDay = today;
            publicAdviceCalls = 0;
        }
        if (publicAdviceCalls >= publicAdviceDailyLimit) return false;
        publicAdviceCalls++;
        return true;
    }

    private TrainingResult result(String id, TrainingQuery query, boolean publicRequest) {
        return switch (query) {
            case SummaryQuery(RunningDateRange range) -> {
                var snapshot = publicRequest ? statistics.publicSummary(range.start(), range.end())
                        : statistics.summary(range.start(), range.end());
                var summary = snapshot.value();
                Long pace = summary.totalDistanceKm() > 0
                        ? Math.round(summary.totalDurationSeconds() / summary.totalDistanceKm()) : null;
                yield new SummaryResult("SUMMARY", id, snapshot.through(), summary.startDate(), summary.endDate(),
                        summary.runCount(), summary.totalDistanceKm(), summary.totalDurationSeconds(), pace, null, null);
            }
            case BestQuery(DistanceType type) -> {
                var snapshot = publicRequest ? statistics.publicPersonalBest(type) : statistics.personalBest(type);
                var pb = snapshot.value();
                var best = pb == null ? null : new BestActivity(pb.date(), pb.activityDistanceKm(),
                        pb.durationSeconds(), pb.averagePaceSecondsPerKm());
                yield new BestResult("PERSONAL_BEST", id, snapshot.through(), type, true, best, null, null);
            }
        };
    }

    private static ResponseEntity<?> invalid(String requestId) {
        return error(400, requestId, "INVALID_REQUEST", "Use a valid date range or standard distance.");
    }

    private static ResponseEntity<?> unavailable(String requestId) {
        return error(503, requestId, "TRAINING_DATA_UNAVAILABLE", "Training data is temporarily unavailable.");
    }

    private record TrainingRequest(TrainingQuery query, AdviceContext advice) {
        static TrainingRequest parse(JsonNode node) {
            if (node.isObject() && node.path("kind").isString()) {
                String kind = node.path("kind").asString();
                try {
                    int fields = node.size() - (node.has("adviceContext") ? 1 : 0);
                    AdviceContext advice = AdviceContext.parse(node.path("adviceContext"));
                    if ("SUMMARY".equals(kind) && fields == 3) {
                        var range = new RunningDateRange(date(node.path("startDate")), date(node.path("endDate")));
                        return new TrainingRequest(new SummaryQuery(range), advice);
                    }
                    if ("PERSONAL_BEST".equals(kind) && fields == 2 && node.path("distanceType").isString()) {
                        var type = DistanceType.valueOf(node.path("distanceType").asString());
                        return new TrainingRequest(new BestQuery(type), advice);
                    }
                } catch (IllegalArgumentException | DateTimeParseException exception) {
                    throw invalidQuery();
                }
            }
            throw invalidQuery();
        }

        private static LocalDate date(JsonNode node) {
            if (!node.isString() || !node.asString().matches("\\d{4}-\\d{2}-\\d{2}")) throw invalidQuery();
            return LocalDate.parse(node.asString());
        }

        private static InvalidRunningQueryException invalidQuery() {
            return new InvalidRunningQueryException("Invalid private training query");
        }
    }

    private sealed interface TrainingQuery permits SummaryQuery, BestQuery {
        String kind();
    }

    private record SummaryQuery(RunningDateRange range) implements TrainingQuery {
        @Override public String kind() { return "SUMMARY"; }
    }

    private record BestQuery(DistanceType distanceType) implements TrainingQuery {
        @Override public String kind() { return "PERSONAL_BEST"; }
    }

    private sealed interface TrainingResult permits SummaryResult, BestResult {
        TrainingResult withAdvice(Evidence evidence, String advice);
    }

    private record SummaryResult(String kind, String requestId, LocalDate dataThrough,
                                 LocalDate startDate, LocalDate endDate, int runCount, double totalDistanceKm,
                                 long totalDurationSeconds, Long averagePaceSecondsPerKm,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) Evidence evidence,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) String advice) implements TrainingResult {
        @Override
        public SummaryResult withAdvice(Evidence evidence, String advice) {
            return new SummaryResult(kind, requestId, dataThrough, startDate, endDate, runCount,
                    totalDistanceKm, totalDurationSeconds, averagePaceSecondsPerKm, evidence, advice);
        }
    }

    private record BestResult(String kind, String requestId, LocalDate dataThrough,
                              DistanceType distanceType, boolean approximate, BestActivity result,
                              @JsonInclude(JsonInclude.Include.NON_NULL) Evidence evidence,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String advice) implements TrainingResult {
        @Override
        public BestResult withAdvice(Evidence evidence, String advice) {
            return new BestResult(kind, requestId, dataThrough, distanceType, approximate, result, evidence, advice);
        }
    }

    private record BestActivity(LocalDate date, double activityDistanceKm, long durationSeconds,
                                int averagePaceSecondsPerKm) {}
}
