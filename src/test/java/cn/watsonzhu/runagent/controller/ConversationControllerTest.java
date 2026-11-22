package cn.watsonzhu.runagent.controller;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import cn.watsonzhu.runagent.agent.RunAgentService;
import cn.watsonzhu.runagent.conversation.ChatHistory;
import cn.watsonzhu.runagent.conversation.ConversationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ConversationControllerTest {
    private static final String BASE = "/api/v1/conversations";
    private static final String TOKEN = "a".repeat(48);
    private static final String ID = UUID.randomUUID().toString();
    private final RunAgentService agent = mock(RunAgentService.class);
    @TempDir Path directory;

    @Test
    void savesReplaysAndResumesAcrossInstancesThenDeletesWithoutResurrection() throws Exception {
        var store = new ConversationStore(directory.toString(), new JsonMapper());
        var client = client(store);
        String firstTurn = UUID.randomUUID().toString();
        String first = request(firstTurn, "半马目标", true);
        client.get().uri(BASE).exchange().expectStatus().isUnauthorized();
        client.get().uri(BASE + "/" + ID).exchange().expectStatus().isUnauthorized();
        client.delete().uri(BASE + "/" + ID).exchange().expectStatus().isUnauthorized();
        client.post().uri(BASE + "/" + ID + "/turns").header("Content-Type", "application/json")
                .bodyValue(first).exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(agent);

        when(agent.privateChat("半马目标")).thenReturn("每周训练三次");
        send(client, first).expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.id").isEqualTo(firstTurn).jsonPath("$.answer").isEqualTo("每周训练三次");
        send(client, first).expectStatus().isOk();
        send(client, request(firstTurn, "另一个问题", true)).expectStatus().isEqualTo(409);
        verify(agent, times(1)).privateChat("半马目标");

        var restarted = client(new ConversationStore(directory.toString(), new JsonMapper()));
        restarted.get().uri(BASE).headers(headers -> headers.setBearerAuth(TOKEN))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$[0].title").isEqualTo("半马目标");
        restarted.get().uri(BASE + "/" + ID).headers(headers -> headers.setBearerAuth(TOKEN))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$[0].answer").isEqualTo("每周训练三次");
        var history = List.of(new ChatHistory.Entry("user", "半马目标"),
                new ChatHistory.Entry("assistant", "每周训练三次"));
        when(agent.privateChat("那长跑呢", "", history)).thenReturn("周日安排长跑");
        send(restarted, request(UUID.randomUUID().toString(), "那长跑呢", false))
                .expectStatus().isOk().expectBody().jsonPath("$.answer").isEqualTo("周日安排长跑");

        restarted.delete().uri(BASE + "/" + ID).headers(headers -> headers.setBearerAuth(TOKEN))
                .exchange().expectStatus().isNoContent();
        restarted.get().uri(BASE + "/" + ID).headers(headers -> headers.setBearerAuth(TOKEN))
                .exchange().expectStatus().isNotFound();
        send(restarted, first).expectStatus().isNotFound();
        restarted.get().uri(BASE).headers(headers -> headers.setBearerAuth(TOKEN))
                .exchange().expectStatus().isOk().expectBody().json("[]");
        verify(agent, times(1)).privateChat("半马目标");
    }

    @Test
    void deletingWhileTheFirstAnswerIsPendingPreventsItsStorage() throws Exception {
        var client = client(new ConversationStore(directory.toString(), new JsonMapper()));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        String body = request(UUID.randomUUID().toString(), "训练计划", true);
        when(agent.privateChat("训练计划")).thenAnswer(ignored -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "计划";
        });

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = workers.submit(() -> send(client, body).expectStatus().isNotFound());
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                client.delete().uri(BASE + "/" + ID).headers(headers -> headers.setBearerAuth(TOKEN))
                        .exchange().expectStatus().isNoContent();
            } finally {
                release.countDown();
            }
            pending.get(5, TimeUnit.SECONDS);
        }
        client.get().uri(BASE + "/" + ID).headers(headers -> headers.setBearerAuth(TOKEN))
                .exchange().expectStatus().isNotFound();
    }

    private WebTestClient client(ConversationStore store) {
        return WebTestClient.bindToController(
                new ConversationController(store, new PrivateChatController(agent, TOKEN), TOKEN)
        ).build();
    }

    private static String request(String id, String message, boolean create) {
        return """
                {"clientTurnId":"%s","message":"%s","create":%s}
                """.formatted(id, message, create);
    }

    private static WebTestClient.ResponseSpec send(WebTestClient client, String body) {
        return client.post().uri(BASE + "/" + ID + "/turns").header("Content-Type", "application/json")
                .headers(headers -> headers.setBearerAuth(TOKEN)).bodyValue(body).exchange();
    }
}
