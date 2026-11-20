package cn.watsonzhu.runagent.review;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "management.server.port=0",
        "spring.ai.openai.api-key=synthetic-key",
        "run-agent.run-review.enabled=true",
        "run-agent.run-review.generation-enabled=true",
        "run-agent.run-review.evidence-base-url=https://fixtures.example",
        "run-agent.public-api.enabled=true",
        "run-agent.public-api.review-ids=42,43",
        "run-agent.public-api.review-daily-limit=1",
        "run-agent.private-chat.token=" + RunReviewHttpIntegrationTest.TOKEN
})
class RunReviewHttpIntegrationTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String REVIEW = "/api/v1/runs/41/review";

    @TempDir static Path directory;
    @LocalServerPort int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("run-agent.run-review.directory", directory::toString);
    }

    @Test
    void realHttpReturnsThePersistedSplitReportUsingTheFrontendContract() {
        var client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .defaultHeaders(headers -> headers.setBearerAuth(TOKEN))
                .build();
        client.get().uri(REVIEW).exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.generationEnabled").isEqualTo(true);
        client.post().uri(REVIEW).exchange().expectStatus().isCreated()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.status").isEqualTo("READY")
                .jsonPath("$.report.mode").isEqualTo("SPLIT_COMPARISON")
                .jsonPath("$.report.generatedAt").isEqualTo("2026-01-07T00:00:00Z")
                .jsonPath("$.report.facts.firstHalfPaceSecondsPerKm").isEqualTo(300)
                .jsonPath("$.report.facts.secondHalfPaceSecondsPerKm").isEqualTo(330)
                .jsonPath("$.report.facts.paceDeltaSecondsPerKm").isEqualTo(30);
        client.get().uri(REVIEW).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.report.runId").isEqualTo(41);
        client.post().uri(REVIEW).exchange().expectStatus().isOk();
    }

    @Test
    void publicRouteIsAllowlistedAndSavesOnlyItsOwnReport() throws Exception {
        var client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        String publicReview = "/api/v1/runs/42/review";
        client.get().uri(REVIEW).exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("RUN_NOT_FOUND");
        client.post().uri(REVIEW).exchange().expectStatus().isNotFound();
        client.get().uri(publicReview).headers(headers -> headers.setBearerAuth("invalid"))
                .exchange().expectStatus().isUnauthorized();
        client.post().uri(publicReview).headers(headers -> headers.setBearerAuth(TOKEN))
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.report.model").isEqualTo("synthetic-model");
        client.get().uri(publicReview).exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("REVIEW_NOT_FOUND");
        client.post().uri(publicReview).bodyValue(Map.of("adviceContext", Map.of("city", " ")))
                .exchange().expectStatus().isBadRequest();
        client.post().uri(publicReview).exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.report.runId").isEqualTo(42)
                .jsonPath("$.report.facts.paceDeltaSecondsPerKm").isEqualTo(30)
                .jsonPath("$.report.model").doesNotExist()
                .jsonPath("$.report.evidenceFingerprint").doesNotExist();
        client.post().uri(publicReview).bodyValue(Map.of("adviceContext", Map.of("city", "上海")))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.weather.status").isEqualTo("UNAVAILABLE");
        client.get().uri(publicReview).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.weather").doesNotExist();
        client.post().uri("/api/v1/runs/43/review").exchange().expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.code").isEqualTo("REVIEW_RATE_LIMITED");
        client.get().uri(publicReview).headers(headers -> headers.setBearerAuth(TOKEN))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.report.model").isEqualTo("synthetic-model");
        assertThat(Files.isRegularFile(directory.resolve("public/42.json"))).isTrue();
        assertThat(Files.isRegularFile(directory.resolve("42.json"))).isTrue();
        assertThat(Files.readString(directory.resolve("public/42.json"))).doesNotContain("上海", "weather");
    }

    @TestConfiguration
    static class SyntheticGenerator {
        @Bean
        @Primary
        RunReviewGenerator fixtureReviewGenerator() {
            var fixture = mock(RunReviewGenerator.class);
            when(fixture.generate(41)).thenReturn(report(41));
            when(fixture.generate(42)).thenReturn(report(42));
            return fixture;
        }

        private static RunReviewReport report(long id) {
            return new RunReviewReport(
                    1, id, Instant.parse("2026-01-07T00:00:00Z"),
                    RunReviewReport.Mode.SPLIT_COMPARISON,
                    new RunReviewReport.Facts(4000, 1260, 315, 300L, 330L, 30L, false),
                    List.of("后半程平均配速比前半程慢 30 秒/公里"),
                    "下次观察末段节奏", "从轻松的节奏起跑，留意后半程能否稳定。",
                    1, "0".repeat(64), "synthetic-model", "1");
        }
    }
}
