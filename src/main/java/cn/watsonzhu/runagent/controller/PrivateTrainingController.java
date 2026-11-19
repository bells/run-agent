package cn.watsonzhu.runagent.controller;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;

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

    private final McpRunningStatistics statistics;
    private final PrivateApiAccess access;
    private final MeterRegistry metrics;
    private final Semaphore queries = new Semaphore(2);

    public PrivateTrainingController(McpRunningStatistics statistics, MeterRegistry metrics,
            @Value("${run-agent.private-chat.token:}") String token) {
        this.statistics = statistics;
        this.metrics = metrics;
        this.access = new PrivateApiAccess(token);
    }

    @PostMapping(value = "/api/v1/training/stats", consumes = "application/json", produces = "application/json")
    public Mono<ResponseEntity<?>> query(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) Mono<JsonNode> body) {
        String requestId = UUID.randomUUID().toString();
        if (!access.allows(authorization)) return Mono.just(PrivateApiAccess.unauthorized(requestId));

        return body.map(TrainingQuery::parse)
                .flatMap(query -> execute(requestId, query))
                .switchIfEmpty(Mono.fromSupplier(() -> invalid(requestId)))
                .onErrorResume(ServerWebInputException.class, exception -> Mono.just(invalid(requestId)))
                .onErrorResume(InvalidRunningQueryException.class, exception -> Mono.just(invalid(requestId)));
    }

    private Mono<ResponseEntity<?>> execute(String requestId, TrainingQuery query) {
        var sample = Timer.start(metrics);
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
                    if (!queries.tryAcquire()) return error(429, requestId, "RATE_LIMITED", "Try again in a few seconds.");
                    try {
                        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result(requestId, query));
                    } finally {
                        queries.release();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(TIMEOUT)
                .onErrorResume(RunningDataUnavailableException.class, exception -> Mono.just(unavailable(requestId)))
                .onErrorResume(TimeoutException.class, exception -> Mono.just(unavailable(requestId)))
                .doOnNext(response -> sample.stop(metrics.timer("runagent.private.training",
                        "kind", query.kind(), "status", Integer.toString(response.getStatusCode().value()))));
    }

    private Object result(String id, TrainingQuery query) {
        return switch (query) {
            case SummaryQuery(RunningDateRange range) -> {
                var snapshot = statistics.summary(range.start(), range.end());
                var summary = snapshot.value();
                Long pace = summary.totalDistanceKm() > 0
                        ? Math.round(summary.totalDurationSeconds() / summary.totalDistanceKm()) : null;
                yield new SummaryResult(id, snapshot.through(), summary.startDate(), summary.endDate(),
                        summary.runCount(), summary.totalDistanceKm(), summary.totalDurationSeconds(), pace);
            }
            case BestQuery(DistanceType type) -> {
                var snapshot = statistics.personalBest(type);
                var pb = snapshot.value();
                var best = pb == null ? null : new BestActivity(pb.date(), pb.activityDistanceKm(),
                        pb.durationSeconds(), pb.averagePaceSecondsPerKm());
                yield new BestResult(id, snapshot.through(), type, best);
            }
        };
    }

    private static ResponseEntity<?> invalid(String requestId) {
        return error(400, requestId, "INVALID_REQUEST", "Use a valid date range or standard distance.");
    }

    private static ResponseEntity<?> unavailable(String requestId) {
        return error(503, requestId, "TRAINING_DATA_UNAVAILABLE", "Training data is temporarily unavailable.");
    }

    private sealed interface TrainingQuery permits SummaryQuery, BestQuery {
        String kind();

        static TrainingQuery parse(JsonNode node) {
            if (node.isObject() && node.path("kind").isString()) {
                String kind = node.path("kind").asString();
                try {
                    if ("SUMMARY".equals(kind) && node.size() == 3) {
                        return new SummaryQuery(new RunningDateRange(date(node.path("startDate")), date(node.path("endDate"))));
                    }
                    if ("PERSONAL_BEST".equals(kind) && node.size() == 2 && node.path("distanceType").isString()) {
                        return new BestQuery(DistanceType.valueOf(node.path("distanceType").asString()));
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

    private record SummaryQuery(RunningDateRange range) implements TrainingQuery {
        @Override public String kind() { return "SUMMARY"; }
    }

    private record BestQuery(DistanceType distanceType) implements TrainingQuery {
        @Override public String kind() { return "PERSONAL_BEST"; }
    }

    private record SummaryResult(String kind, String requestId, LocalDate dataThrough,
                                 LocalDate startDate, LocalDate endDate, int runCount, double totalDistanceKm,
                                 long totalDurationSeconds, Long averagePaceSecondsPerKm) {
        SummaryResult(String requestId, LocalDate dataThrough, LocalDate startDate, LocalDate endDate,
                int runCount, double totalDistanceKm, long totalDurationSeconds, Long averagePaceSecondsPerKm) {
            this("SUMMARY", requestId, dataThrough, startDate, endDate, runCount,
                    totalDistanceKm, totalDurationSeconds, averagePaceSecondsPerKm);
        }
    }

    private record BestResult(String kind, String requestId, LocalDate dataThrough,
                              DistanceType distanceType, boolean approximate, BestActivity result) {
        BestResult(String requestId, LocalDate dataThrough, DistanceType distanceType, BestActivity result) {
            this("PERSONAL_BEST", requestId, dataThrough, distanceType, true, result);
        }
    }

    private record BestActivity(LocalDate date, double activityDistanceKm, long durationSeconds,
                                int averagePaceSecondsPerKm) {}
}
