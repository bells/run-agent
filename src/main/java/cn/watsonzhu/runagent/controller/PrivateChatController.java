package cn.watsonzhu.runagent.controller;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import cn.watsonzhu.runagent.agent.RunAgentService;
import cn.watsonzhu.runagent.evidence.AdviceContext;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService.Evidence;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Autowired;
import cn.watsonzhu.runagent.exception.AiRequestException;
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
@ConditionalOnProperty(name = "run-agent.private-chat.enabled", havingValue = "true")
public class PrivateChatController {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final long INTERVAL_NANOS = Duration.ofSeconds(4).toNanos();
    private final RunAgentService agent;
    private final PrivateApiAccess access;
    private final RunningEvidenceService evidence;
    private final boolean publicEnabled;
    private final Admission owner = new Admission(Integer.MAX_VALUE);
    private final Admission visitor;

    @Autowired
    public PrivateChatController(RunAgentService agent, @Value("${run-agent.private-chat.token:}") String token,
            RunningEvidenceService evidence, @Value("${run-agent.public-api.enabled:false}") boolean publicEnabled,
            @Value("${run-agent.public-api.chat-daily-limit:40}") int publicDailyLimit) {
        this.agent = agent;
        this.access = new PrivateApiAccess(token);
        this.evidence = evidence;
        this.publicEnabled = publicEnabled;
        this.visitor = new Admission(publicDailyLimit);
    }

    PrivateChatController(RunAgentService agent, String token) {
        this(agent, token, null, false, 40);
    }

    @PostMapping(value = "/api/v1/chat", consumes = "application/json", produces = "application/json")
    public Mono<ResponseEntity<?>> chat(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) Mono<JsonNode> body) {
        String requestId = UUID.randomUUID().toString();
        boolean anonymous = publicEnabled && authorization == null;
        if (!anonymous && !access.allows(authorization)) {
            return Mono.just(PrivateApiAccess.unauthorized(requestId));
        }
        return body.flatMap(node -> {
                    var request = PrivateChatRequest.from(node);
                    return request == null ? Mono.just(invalid(requestId))
                            : answer(requestId, request, anonymous ? visitor : owner);
                })
                .switchIfEmpty(Mono.fromSupplier(() -> invalid(requestId)))
                .onErrorResume(ServerWebInputException.class, exception -> Mono.just(invalid(requestId)));
    }

    private Mono<ResponseEntity<?>> answer(String requestId, PrivateChatRequest request, Admission admission) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
                    if (!admission.tryStart()) {
                        return error(429, requestId, "RATE_LIMITED", "The request limit has been reached. Try again later.");
                    }
                    try {
                        var bundle = evidence != null && (publicEnabled || !request.context().equals(AdviceContext.NONE))
                                ? evidence.collect(request.message(), request.context().withChatHints(request.message()))
                                : null;
                        String content = bundle == null ? agent.privateChat(request.message())
                                : agent.privateChat(request.message(), bundle.promptContext());
                        if (bundle != null && content != null && !bundle.allowsAnswer(content)) {
                            content = "这次回答包含过长的原文。可以换个问法，我可以概括相关训练原则。";
                        }
                        return content == null || content.isBlank()
                                ? error(502, requestId, "AI_SERVICE_ERROR", "The AI service returned no answer.")
                                : ResponseEntity.ok().cacheControl(CacheControl.noStore())
                                        .body(new PrivateChatResponse(requestId, content,
                                                bundle == null ? null : bundle.evidence()));
                    } finally {
                        admission.finish();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(TIMEOUT)
                .onErrorResume(AiRequestException.class, exception ->
                        Mono.just(error(502, requestId, "AI_SERVICE_ERROR", "The AI service is temporarily unavailable.")))
                .onErrorResume(TimeoutException.class, exception ->
                        Mono.just(error(504, requestId, "AI_SERVICE_ERROR", "The AI request timed out.")));
    }

    private static final class Admission {
        private final int dailyLimit;
        private LocalDate day = LocalDate.now(ZoneOffset.UTC);
        private int requests;
        private boolean inFlight;
        private long lastStart = System.nanoTime() - INTERVAL_NANOS;

        private Admission(int dailyLimit) {
            if (dailyLimit < 1) throw new IllegalArgumentException("Chat limit must be positive");
            this.dailyLimit = dailyLimit;
        }

        synchronized boolean tryStart() {
            var today = LocalDate.now(ZoneOffset.UTC);
            if (!day.equals(today)) {
                day = today;
                requests = 0;
            }
            long now = System.nanoTime();
            if (inFlight || now - lastStart < INTERVAL_NANOS || requests >= dailyLimit) return false;
            inFlight = true;
            lastStart = now;
            requests++;
            return true;
        }

        synchronized void finish() {
            inFlight = false;
        }
    }

    private static ResponseEntity<?> invalid(String requestId) {
        return error(400, requestId, "INVALID_REQUEST", "Enter a message of at most 4000 characters and valid optional city and time.");
    }

    private record PrivateChatRequest(String message, AdviceContext context) {
        static PrivateChatRequest from(JsonNode node) {
            if (!node.isObject() || node.size() != (node.has("context") ? 2 : 1) || !node.path("message").isString()) {
                return null;
            }
            String message = node.path("message").asString();
            if (message.isBlank() || message.codePointCount(0, message.length()) > 4000) {
                return null;
            }
            try {
                return new PrivateChatRequest(message, AdviceContext.parse(node.path("context")));
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record PrivateChatResponse(String requestId, String content, Evidence evidence) {}
}
