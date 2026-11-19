package cn.watsonzhu.runagent.controller;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=synthetic-key",
        "run-agent.run-review.enabled=true",
        "run-agent.run-review.generation-enabled=false",
        "run-agent.private-chat.token=" + RunReviewRouteTest.TOKEN
})
class RunReviewRouteTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String URL = "/api/v1/runs/{id}/review";

    @TempDir static Path directory;
    @Autowired ApplicationContext context;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("run-agent.run-review.directory", directory::toString);
    }

    @Test
    void authenticatesBeforeDisclosingRecordStateOrAcceptingIdentifiers() {
        client().get().uri(URL, 7).exchange().expectStatus().isUnauthorized()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
        client().post().uri(URL, "bad").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void stoppedGenerationKeepsReadsAvailableAndMakesNoModelRequest() {
        client().get().uri(URL, 7).headers(headers -> headers.setBearerAuth(TOKEN))
                .exchange().expectStatus().isNotFound().expectBody()
                .jsonPath("$.code").isEqualTo("REVIEW_NOT_FOUND");
        client().post().uri(URL, 7).headers(headers -> headers.setBearerAuth(TOKEN))
                .exchange().expectStatus().isEqualTo(503).expectBody()
                .jsonPath("$.code").isEqualTo("REVIEW_GENERATION_DISABLED");
    }

    @Test
    void doesNotAcceptPathIdentifiersOutsideTheBrowserSafeIntegerRange() {
        client().get().uri(URL, "9007199254740992").headers(headers -> headers.setBearerAuth(TOKEN))
                .exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_RUN_ID");
        client().get().uri(URL, "001").headers(headers -> headers.setBearerAuth(TOKEN))
                .exchange().expectStatus().isBadRequest();
    }

    private WebTestClient client() {
        return WebTestClient.bindToApplicationContext(context).build();
    }
}
