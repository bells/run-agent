package cn.watsonzhu.runagent.controller;

import java.util.List;
import cn.watsonzhu.runagent.agent.RunAgentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.Mockito.*;

class PrivateChatControllerTest {
    private static final String TOKEN = "a".repeat(48);
    private final RunAgentService agent = mock(RunAgentService.class);
    private final WebTestClient client = WebTestClient.bindToController(new PrivateChatController(agent, TOKEN)).build();

    @Test
    void rejectsMissingOrInvalidCredentialsBeforeCallingProvider() {
        post("{invalid}", null).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
        post("{\"message\":\"hello\"}", "wrong").expectStatus().isUnauthorized();
        verifyNoInteractions(agent);
    }

    @Test
    void validatesTheExactRequestShapeBeforeCallingProvider() {
        for (String body : List.of("{}", "{\"message\":\"  \"}", "{\"message\":3}",
                "{\"message\":\"hello\",\"extra\":true}", "{\"message\":\"" + "x".repeat(4001) + "\"}")) {
            post(body, TOKEN).expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST");
        }
        verifyNoInteractions(agent);
    }

    @Test
    void returnsTheVersionedResponseAndBoundsRepeatedCalls() {
        when(agent.privateChat("hello")).thenReturn("你好");
        post("{\"message\":\"hello\"}", TOKEN).expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.content").isEqualTo("你好").jsonPath("$.requestId").isNotEmpty();
        post("{\"message\":\"hello\"}", TOKEN).expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");
        verify(agent).privateChat("hello");
    }

    private WebTestClient.ResponseSpec post(String body, String token) {
        var request = client.post().uri("/api/v1/chat").header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.bodyValue(body).exchange();
    }
}
