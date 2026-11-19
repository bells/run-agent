package cn.watsonzhu.runagent.controller;

import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import cn.watsonzhu.runagent.review.RunReviewCoordinator;
import cn.watsonzhu.runagent.review.RunReviewReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/runs/{runId}/review")
@ConditionalOnProperty(name = "run-agent.run-review.enabled", havingValue = "true")
public class RunReviewController {
    private static final Pattern RUN_ID = Pattern.compile("[1-9][0-9]{0,15}");
    private static final long MAX_SAFE_RUN_ID = 9_007_199_254_740_991L;

    private final RunReviewCoordinator reviews;
    private final PrivateApiAccess access;

    public RunReviewController(RunReviewCoordinator reviews,
            @Value("${run-agent.private-chat.token:}") String token) {
        this.reviews = reviews;
        this.access = new PrivateApiAccess(token);
    }

    @GetMapping
    public Mono<ResponseEntity<?>> read(@PathVariable String runId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return authorized(runId, authorization, id -> Mono.fromCallable(() -> reviews.read(id)).subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping
    public Mono<ResponseEntity<?>> generate(@PathVariable String runId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return authorized(runId, authorization, id -> Mono.fromCallable(() -> reviews.generate(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(pending -> Mono.fromFuture(pending, true)));
    }

    private Mono<ResponseEntity<?>> authorized(String runId, String authorization,
            Function<Long, Mono<RunReviewCoordinator.Reply>> operation) {
        String requestId = UUID.randomUUID().toString();
        if (!access.allows(authorization)) return Mono.just(PrivateApiAccess.unauthorized(requestId));
        if (!RUN_ID.matcher(runId).matches()) return Mono.just(invalid(requestId));
        long id = Long.parseLong(runId);
        if (id > MAX_SAFE_RUN_ID) return Mono.just(invalid(requestId));
        return operation.apply(id).map(reply -> response(requestId, reply));
    }

    private static ResponseEntity<?> response(String requestId, RunReviewCoordinator.Reply reply) {
        var builder = ResponseEntity.status(reply.status()).cacheControl(CacheControl.noStore());
        if ("READY".equals(reply.state())) {
            return builder.body(new Ready("READY", requestId, reply.report()));
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
            case "EVIDENCE_UNAVAILABLE" -> "Running evidence is temporarily unavailable.";
            case "REVIEW_STORAGE_UNAVAILABLE" -> "Review storage is temporarily unavailable.";
            default -> "Review generation failed. Try again.";
        };
    }

    private record Ready(String status, String requestId, RunReviewReport report) {}
    private record Generating(String status, String requestId) {}
    private record Failed(String status, String requestId, String code, boolean generationEnabled) {}
    private record NotFound(String requestId, String code, String message, boolean generationEnabled) {}
}
