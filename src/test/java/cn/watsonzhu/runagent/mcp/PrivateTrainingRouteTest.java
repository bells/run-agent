package cn.watsonzhu.runagent.mcp;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"spring.ai.openai.api-key=synthetic-key",
        "run-agent.private-training.enabled=true", "run-agent.private-chat.enabled=true",
        "run-agent.legacy-api.enabled=false",
        "run-agent.private-chat.token=" + PrivateTrainingRouteTest.TOKEN})
class PrivateTrainingRouteTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String RANGE = """
            {"kind":"SUMMARY","startDate":"2026-11-01","endDate":"2026-11-07"}
            """;

    @Autowired ApplicationContext context;

    @Test
    void authorizationPrecedesBodyDecoding() {
        post("{invalid}", null).expectStatus().isUnauthorized()
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/chat", "/api/agent", "/api/workflows/training-analysis",
            "/api/multi-agent/training-advice", "/api/intent", "/api/knowledge/ask"})
    void legacyModelRoutesCannotAccessPrivateSnapshot(String path) {
        WebTestClient.bindToApplicationContext(context).build().post().uri(path)
                .header("Content-Type", "application/json").bodyValue("{}")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void summaryShowsWeightedPaceAndSnapshotDate() {
        post(RANGE, TOKEN).expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.kind").isEqualTo("SUMMARY")
                .jsonPath("$.requestId").isNotEmpty()
                .jsonPath("$.dataThrough").isEqualTo("2026-11-10")
                .jsonPath("$.runCount").isEqualTo(2)
                .jsonPath("$.totalDistanceKm").isEqualTo(12.5)
                .jsonPath("$.totalDurationSeconds").isEqualTo(4500)
                .jsonPath("$.averagePaceSecondsPerKm").isEqualTo(360);
    }

    @Test
    void emptyRangeIsDistinctFromAnUnavailableSnapshot() {
        post("""
                {"kind":"SUMMARY","startDate":"2026-11-08","endDate":"2026-11-09"}
                """, TOKEN).expectStatus().isOk().expectBody()
                .jsonPath("$.runCount").isEqualTo(0)
                .jsonPath("$.averagePaceSecondsPerKm").isEmpty();
        post("""
                {"kind":"SUMMARY","startDate":"2026-11-10","endDate":"2026-11-11"}
                """, TOKEN).expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("TRAINING_DATA_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("Training data is temporarily unavailable.");
    }

    @Test
    void personalBestIsApproximateAndMissingMatchIsExplicit() {
        post("""
                {"kind":"PERSONAL_BEST","distanceType":"FIVE_K"}
                """, TOKEN).expectStatus().isOk().expectBody()
                .jsonPath("$.kind").isEqualTo("PERSONAL_BEST")
                .jsonPath("$.approximate").isEqualTo(true)
                .jsonPath("$.result.date").isEqualTo("2026-11-02")
                .jsonPath("$.result.activityDistanceKm").isEqualTo(5.1)
                .jsonPath("$.result.averagePaceSecondsPerKm").isEqualTo(300);
        post("""
                {"kind":"PERSONAL_BEST","distanceType":"TEN_K"}
                """, TOKEN).expectStatus().isOk().expectBody()
                .jsonPath("$.approximate").isEqualTo(true)
                .jsonPath("$.result").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"kind\":\"SUMMARY\",\"startDate\":\"2025-01-01\",\"endDate\":\"2026-12-31\"}",
            "{\"kind\":\"SUMMARY\",\"startDate\":\"2026-11-31\",\"endDate\":\"2026-12-01\"}",
            "{\"kind\":\"SUMMARY\",\"startDate\":\"2026-11-02\",\"endDate\":\"2026-11-01\"}",
            "{\"kind\":\"PERSONAL_BEST\",\"distanceType\":\"CUSTOM\"}",
            "{\"kind\":\"PERSONAL_BEST\",\"distanceType\":\"FIVE_K\",\"question\":\"free text\"}"
    })
    void invalidRequestsFailWithStableContract(String body) {
        post(body, TOKEN).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST");
    }

    private WebTestClient.ResponseSpec post(String body, String token) {
        var request = WebTestClient.bindToApplicationContext(context).build().post()
                .uri("/api/v1/training/stats").header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.bodyValue(body).exchange();
    }

    @TestConfiguration
    static class SyntheticRunning {
        @Bean McpCatalog syntheticRunningCatalog() {
            var client = McpCatalogTest.client("running", "running-mcp-server",
                    "getRunningSummary", "getRecentRuns", "getPersonalBest");
            when(client.callTool(any())).thenAnswer(call -> {
                McpSchema.CallToolRequest request = call.getArgument(0);
                String content = switch (request.name()) {
                    case "getRunningSummary" -> switch (String.valueOf(request.arguments().get("startDate"))) {
                        case "2026-11-01" -> """
                                {"startDate":"2026-11-01","endDate":"2026-11-07","runCount":2,
                                 "totalDistanceKm":12.5,"totalDurationSeconds":4500,"dataThrough":"2026-11-10"}
                                """;
                        case "2026-11-08" -> """
                                {"startDate":"2026-11-08","endDate":"2026-11-09","runCount":0,
                                 "totalDistanceKm":0,"totalDurationSeconds":0,"dataThrough":"2026-11-10"}
                                """;
                        default -> """
                                {"error":"RUNNING_DATA_UNAVAILABLE","message":"internal fixture path"}
                                """;
                    };
                    case "getPersonalBest" -> switch (String.valueOf(request.arguments().get("distanceType"))) {
                        case "FIVE_K" -> """
                                {"distanceType":"FIVE_K","date":"2026-11-02","activityDistanceKm":5.1,
                                 "durationSeconds":1530,"averagePaceSecondsPerKm":300,"approximate":true,
                                 "calculationBasis":"WHOLE_ACTIVITY_DISTANCE_MATCH","dataThrough":"2026-11-10"}
                                """;
                        default -> """
                                {"distanceType":"TEN_K","date":null,"activityDistanceKm":0,
                                 "durationSeconds":0,"averagePaceSecondsPerKm":null,"approximate":true,
                                 "calculationBasis":"NO_MATCHING_ACTIVITY","dataThrough":"2026-11-10"}
                                """;
                    };
                    default -> throw new AssertionError("Unexpected tool: " + request.name());
                };
                return McpSchema.CallToolResult.builder().addTextContent(content).build();
            });
            return new McpCatalog(List.of(client), new McpPolicy("mcp").filter(), ToolContextToMcpMetaConverter.noOp());
        }
    }
}
