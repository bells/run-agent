package cn.watsonzhu.runagent.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import cn.watsonzhu.runagent.agent.RunAgentService;
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

@RestController
@ConditionalOnProperty(name = "run-agent.private-chat.enabled", havingValue = "true")
public class PrivateChatController {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final long INTERVAL_NANOS = Duration.ofSeconds(4).toNanos();
    private final RunAgentService agent;
    private final byte[] credential;
    private boolean inFlight;
    private long lastStart = System.nanoTime() - INTERVAL_NANOS;

    public PrivateChatController(RunAgentService agent, @Value("${run-agent.private-chat.token:}") String token) {
        if (!token.matches("[a-f0-9]{48}")) {
            throw new IllegalArgumentException("Private chat requires a 48-character hexadecimal access token");
        }
        this.agent = agent;
        credential = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(value = "/api/v1/chat", consumes = "application/json", produces = "application/json")
    public Mono<ResponseEntity<?>> chat(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) Mono<JsonNode> body) {
        String requestId = UUID.randomUUID().toString();
        if (authorization == null || !MessageDigest.isEqual(credential, authorization.getBytes(StandardCharsets.UTF_8))) {
            return Mono.just(error(401, requestId, "UNAUTHORIZED", "A valid access token is required."));
        }
        return body.flatMap(node -> {
                    var request = PrivateChatRequest.from(node);
                    return request == null ? Mono.just(invalid(requestId)) : answer(requestId, request);
                })
                .switchIfEmpty(Mono.fromSupplier(() -> invalid(requestId)))
                .onErrorResume(ServerWebInputException.class, exception -> Mono.just(invalid(requestId)));
    }

    private Mono<ResponseEntity<?>> answer(String requestId, PrivateChatRequest request) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
                    if (!tryStart()) {
                        return error(429, requestId, "RATE_LIMITED", "Try again in a few seconds.");
                    }
                    try {
                        String content = agent.privateChat(request.message());
                        return content == null || content.isBlank()
                                ? error(502, requestId, "AI_SERVICE_ERROR", "The AI service returned no answer.")
                                : ResponseEntity.ok().cacheControl(CacheControl.noStore())
                                        .body(new PrivateChatResponse(requestId, content));
                    } finally {
                        finish();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(TIMEOUT)
                .onErrorResume(AiRequestException.class, exception ->
                        Mono.just(error(502, requestId, "AI_SERVICE_ERROR", "The AI service is temporarily unavailable.")))
                .onErrorResume(TimeoutException.class, exception ->
                        Mono.just(error(504, requestId, "AI_SERVICE_ERROR", "The AI request timed out.")));
    }

    private synchronized boolean tryStart() {
        long now = System.nanoTime();
        if (inFlight || now - lastStart < INTERVAL_NANOS) {
            return false;
        }
        inFlight = true;
        lastStart = now;
        return true;
    }

    private synchronized void finish() {
        inFlight = false;
    }

    private static ResponseEntity<?> invalid(String requestId) {
        return error(400, requestId, "INVALID_REQUEST", "Enter a message of at most 4000 characters.");
    }

    private static ResponseEntity<?> error(int status, String requestId, String code, String message) {
        var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (status == 401) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(new PrivateChatError(requestId, code, message));
    }

    private record PrivateChatRequest(String message) {
        static PrivateChatRequest from(JsonNode node) {
            if (!node.isObject() || node.size() != 1 || !node.path("message").isString()) {
                return null;
            }
            String message = node.path("message").asString();
            if (message.isBlank() || message.codePointCount(0, message.length()) > 4000) {
                return null;
            }
            return new PrivateChatRequest(message);
        }
    }

    private record PrivateChatResponse(String requestId, String content) {}
    private record PrivateChatError(String requestId, String code, String message) {}
}
