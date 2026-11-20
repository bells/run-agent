package cn.watsonzhu.runagent.controller;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import cn.watsonzhu.runagent.evidence.AdviceContext;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService.Knowledge;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService.Weather;
import cn.watsonzhu.runagent.review.RunReviewCoordinator;
import cn.watsonzhu.runagent.review.RunReviewReport;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/runs/{runId}/review")
@ConditionalOnProperty(name = "run-agent.run-review.enabled", havingValue = "true")
public class RunReviewController {
    private static final Pattern RUN_ID = Pattern.compile("[1-9][0-9]{0,15}");
    private static final long MAX_SAFE_RUN_ID = 9_007_199_254_740_991L;

    private final RunReviewCoordinator reviews;
    private final ObjectProvider<RunReviewCoordinator> publicReviews;
    private final Set<Long> publicRunIds;
    private final PrivateApiAccess access;
    private final RunningEvidenceService runningEvidence;
    private final Semaphore publicWeather = new Semaphore(1);

    public RunReviewController(@Qualifier("runReviewCoordinator") RunReviewCoordinator reviews,
            @Qualifier("publicRunReviewCoordinator") ObjectProvider<RunReviewCoordinator> publicReviews,
            RunningEvidenceService runningEvidence,
            @Value("${run-agent.public-api.review-ids:}") String publicRunIds,
            @Value("${run-agent.private-chat.token:}") String token) {
        this.reviews = reviews;
        this.publicReviews = publicReviews;
        this.runningEvidence = runningEvidence;
        this.publicRunIds = parsePublicRunIds(publicRunIds);
        this.access = new PrivateApiAccess(token);
    }

    @GetMapping
    public Mono<ResponseEntity<?>> read(@PathVariable String runId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return authorized(runId, authorization, request -> Mono.fromCallable(() -> request.coordinator().read(request.runId()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(reply -> response(request, reply, null)));
    }

    @PostMapping
    public Mono<ResponseEntity<?>> generate(@PathVariable String runId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) Mono<JsonNode> body) {
        return authorized(runId, authorization, request -> body.map(RunReviewController::adviceContext)
                .defaultIfEmpty(AdviceContext.NONE)
                .flatMap(advice -> generate(request, advice))
                .onErrorResume(IllegalArgumentException.class, failure -> Mono.just(invalidAdvice(request)))
                .onErrorResume(ServerWebInputException.class, failure -> Mono.just(invalidAdvice(request))));
    }

    private Mono<ResponseEntity<?>> generate(ReviewRequest request, AdviceContext advice) {
        return Mono.fromCallable(() -> request.coordinator().generate(request.runId()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(pending -> Mono.fromFuture(pending, true))
                .flatMap(reply -> {
                    ResponseEntity<?> baseResponse = response(request, reply, null);
                    if (!"READY".equals(reply.state()) || advice.city() == null) {
                        return Mono.just(baseResponse);
                    }
                    return Mono.fromCallable(() -> weather(request, advice)).subscribeOn(Schedulers.boundedElastic())
                            .<ResponseEntity<?>>map(weather -> response(request, reply, weather))
                            .defaultIfEmpty(baseResponse)
                            .onErrorReturn(baseResponse);
                });
    }

    private Weather weather(ReviewRequest request, AdviceContext advice) {
        if (request.owner()) return runningEvidence.weather(advice);
        if (!publicWeather.tryAcquire()) return null;
        try {
            return runningEvidence.weather(advice);
        } finally {
            publicWeather.release();
        }
    }

    private static AdviceContext adviceContext(JsonNode body) {
        if (!body.isObject() || (!body.isEmpty() && (body.size() != 1 || !body.has("adviceContext")))) {
            throw new IllegalArgumentException("Invalid review request");
        }
        return AdviceContext.parse(body.get("adviceContext"));
    }

    private static ResponseEntity<?> invalidAdvice(ReviewRequest request) {
        return PrivateApiAccess.error(400, request.requestId(), "INVALID_REQUEST", "Enter a valid city and local running time.");
    }

    private Mono<ResponseEntity<?>> authorized(String runId, String authorization,
            Function<ReviewRequest, Mono<ResponseEntity<?>>> operation) {
        String requestId = UUID.randomUUID().toString();
        boolean owner = access.allows(authorization);
        var coordinator = owner ? reviews : authorization == null ? publicReviews.getIfAvailable() : null;
        if (coordinator == null) return Mono.just(PrivateApiAccess.unauthorized(requestId));
        long id = parseRunId(runId);
        if (id == 0) return Mono.just(invalid(requestId));
        if (!owner && !publicRunIds.contains(id)) {
            return Mono.just(PrivateApiAccess.error(404, requestId, "RUN_NOT_FOUND", message("RUN_NOT_FOUND")));
        }
        return operation.apply(new ReviewRequest(requestId, id, owner, coordinator));
    }

    private static long parseRunId(String value) {
        if (!RUN_ID.matcher(value).matches()) return 0;
        long id = Long.parseLong(value);
        return id <= MAX_SAFE_RUN_ID ? id : 0;
    }

    private static Set<Long> parsePublicRunIds(String value) {
        if (value.isBlank()) return Set.of();
        return Arrays.stream(value.split(",", -1)).map(String::strip).map(id -> {
            long parsed = parseRunId(id);
            if (parsed == 0) throw new IllegalArgumentException("Public review allowlist contains an invalid run ID");
            return parsed;
        }).collect(Collectors.toUnmodifiableSet());
    }

    private static ResponseEntity<?> response(ReviewRequest request, RunReviewCoordinator.Reply reply, Weather weather) {
        String requestId = request.requestId();
        var builder = ResponseEntity.status(reply.status()).cacheControl(CacheControl.noStore());
        if ("READY".equals(reply.state())) {
            return request.owner()
                    ? builder.body(new Ready<>("READY", requestId, reply.report(), weather))
                    : builder.body(new Ready<>("READY", requestId, PublicReport.from(reply.report()), weather));
        }
        if ("GENERATING".equals(reply.state())) {
            return builder.header(HttpHeaders.RETRY_AFTER, "2").body(new Generating("GENERATING", requestId));
        }
        if ("FAILED".equals(reply.state())) {
            return builder.body(new Failed("FAILED", requestId, reply.code(), reply.generationEnabled()));
        }
        if ("REVIEW_NOT_FOUND".equals(reply.code())) {
            return builder.body(new NotFound(requestId, reply.code(), message(reply.code()), reply.generationEnabled()));
        }
        return PrivateApiAccess.error(reply.status(), requestId, reply.code(), message(reply.code()));
    }

    private static ResponseEntity<?> invalid(String requestId) {
        return PrivateApiAccess.error(400, requestId, "INVALID_RUN_ID", "Select a valid running record.");
    }

    private static String message(String code) {
        return switch (code) {
            case "REVIEW_NOT_FOUND" -> "This run has no review.";
            case "RUN_NOT_FOUND" -> "This running record is unavailable.";
            case "REVIEW_GENERATION_DISABLED" -> "Review generation is disabled.";
            case "REVIEW_BUSY" -> "A review is already being generated. Try again shortly.";
            case "REVIEW_RATE_LIMITED" -> "Public review generation is temporarily rate limited.";
            case "EVIDENCE_UNAVAILABLE" -> "Running evidence is temporarily unavailable.";
            case "REVIEW_STORAGE_UNAVAILABLE" -> "Review storage is temporarily unavailable.";
            default -> "Review generation failed. Try again.";
        };
    }

    private record ReviewRequest(String requestId, long runId, boolean owner, RunReviewCoordinator coordinator) {}
    private record Ready<T>(String status, String requestId, T report,
                            @JsonInclude(JsonInclude.Include.NON_NULL) Weather weather) {}
    private record PublicReport(int schemaVersion, long runId, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant generatedAt,
                                RunReviewReport.Mode mode, RunReviewReport.Facts facts, List<String> observations,
                                String improvement, String nextRun, @JsonInclude(JsonInclude.Include.NON_NULL) Knowledge knowledge) {
        static PublicReport from(RunReviewReport report) {
            return new PublicReport(report.schemaVersion(), report.runId(), report.generatedAt(), report.mode(), report.facts(),
                    report.observations(), report.improvement(), report.nextRun(), report.knowledge());
        }
    }
    private record Generating(String status, String requestId) {}
    private record Failed(String status, String requestId, String code, boolean generationEnabled) {}
    private record NotFound(String requestId, String code, String message, boolean generationEnabled) {}
}
