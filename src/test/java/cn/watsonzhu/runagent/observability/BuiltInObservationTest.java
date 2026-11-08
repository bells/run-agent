package cn.watsonzhu.runagent.observability;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.sun.net.httpserver.HttpServer;
import cn.watsonzhu.runagent.agent.RunAgentService;
import cn.watsonzhu.runagent.evaluation.EvaluationFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"AI_GATEWAY_API_KEY=synthetic-key", "AI_GATEWAY_MODEL=synthetic-gateway",
    "spring.ai.openai.max-retries=0", "management.server.port=0", "management.tracing.export.enabled=true", "management.tracing.sampling.probability=1.0"})
class BuiltInObservationTest {
    private static final HttpServer SERVER = server();
    private static Path activities;
    @Autowired RunAgentService agent;
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired MeterRegistry meters;
    @Autowired InMemorySpanExporter exporter;
    @Autowired SdkTracerProvider provider;
    @Autowired cn.watsonzhu.runagent.workflow.TrainingAnalysisWorkflow workflow;
    @Autowired cn.watsonzhu.runagent.multiagent.MultiAgentCoordinator multiAgent;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) throws Exception {
        activities = Files.createTempFile("synthetic-observation-", ".json");
        Files.writeString(activities, "[{\"run_id\":1,\"type\":\"Run\",\"distance\":5000,\"moving_time\":\"0:30:00\",\"start_date_local\":\"2026-01-05 08:00:00\"}]");
        properties.add("RUNNING_DATA_PATH", () -> activities.toString());
        properties.add("AI_GATEWAY_BASE_URL", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1");
    }
    @AfterAll static void close() throws Exception { SERVER.stop(0); Files.deleteIfExists(activities); }
    @Test void actualGatewayProducesBuiltInModelToolUsageAndBusinessSpansWithoutContent() {
        var http = org.springframework.test.web.reactive.server.WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port")).build();
        http.post().uri("/api/agent").bodyValue(java.util.Map.of("message", "synthetic-secret-question"))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.toolCallCount").isEqualTo(1);
        provider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
        var spans = exporter.getFinishedSpanItems();
        assertThat(spans.stream().map(io.opentelemetry.sdk.trace.data.SpanData::getName)).anyMatch(n -> n.contains("getRunningSummary"));
        assertThat(meters.getMeters().stream().map(m -> m.getId().getName())).contains("spring.ai.chat.client", "gen_ai.client.operation", "gen_ai.client.token.usage", "spring.ai.tool", "runagent.agent");
        for (var span : spans) System.out.println("SYNTHETIC_SPAN name=" + span.getName() + " id=" + span.getSpanId() + " parent=" + span.getParentSpanId());
        assertThat(spans).allSatisfy(span -> assertThat(span.getAttributes().toString() + span.getEvents()).doesNotContain("synthetic-secret-question", "synthetic-secret-answer", "synthetic-key"));
    }
    @Test void providerErrorsCannotExportRawPayloadInExceptionEvents() {
        assertThatThrownBy(() -> agent.agent(null, "provider-failure")).isInstanceOf(cn.watsonzhu.runagent.exception.AiRequestException.class);
        provider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(exporter.getFinishedSpanItems()).allSatisfy(span -> assertThat(span.getAttributes().toString() + span.getEvents()).doesNotContain("synthetic-secret-provider-payload"));
    }
    @Test void combinedGraphExportsFiveNodesVectorQueryAndIsolatedAnalysisSpans() {
        var response = workflow.execute(new cn.watsonzhu.runagent.model.TrainingAnalysisRequest("轻松 synthetic-secret-workflow",
            cn.watsonzhu.runagent.workflow.WorkflowMode.COMBINED, java.time.LocalDate.parse("2026-01-01"), java.time.LocalDate.parse("2026-01-31")));
        assertThat(response.executedNodes()).hasSize(5);
        provider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
        var spans = exporter.getFinishedSpanItems().stream().filter(span -> span.getAttributes().asMap().values().stream().anyMatch(v -> v.toString().equals(response.executionId()))).toList();
        assertThat(spans.stream().filter(span -> span.getName().equals("runagent.workflow.node"))).hasSize(5);
        assertThat(meters.getMeters().stream().map(m -> m.getId().getName())).contains("db.vector.client.operation", "runagent.workflow", "runagent.workflow.node");
        assertThat(exporter.getFinishedSpanItems()).allSatisfy(span -> assertThat(span.getAttributes().toString() + span.getEvents()).doesNotContain("synthetic-secret-workflow", "synthetic-secret-book-evidence"));
    }
    @Test void multiAgentExportsActualCycleWithModelToolRetrievalAndPrivacy() {
        var http = org.springframework.test.web.reactive.server.WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port"))
                .responseTimeout(java.time.Duration.ofSeconds(10)).build();
        var response = http.post().uri("/api/multi-agent/training-advice").bodyValue(java.util.Map.of(
                "question", "轻松 synthetic-secret-multi-question", "startDate", "2026-01-01", "endDate", "2026-01-31"))
                .exchange().expectStatus().isOk().expectBody(cn.watsonzhu.runagent.model.MultiAgentResponse.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        assertThat(response.delegationPath()).containsExactly("supervisor", "runningAnalyst", "supervisor", "knowledgeCoach", "supervisor", "synthesize");
        provider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
        var spans = exporter.getFinishedSpanItems();
        var business = spans.stream().filter(span -> span.getAttributes().asMap().values().stream()
                .anyMatch(v -> v.toString().equals(response.executionId()))).toList();
        assertThat(business.stream().filter(s -> s.getName().equals("runagent.multi_agent.specialist"))).hasSize(5);
        var root = business.stream().filter(s -> s.getName().equals("runagent.multi_agent")).findFirst().orElseThrow();
        assertThat(business.stream().filter(s -> s.getName().equals("runagent.multi_agent.specialist")))
                .allSatisfy(s -> assertThat(s.getParentSpanId()).isEqualTo(root.getSpanId()));
        var sameTrace = spans.stream().filter(s -> s.getTraceId().equals(root.getTraceId())).toList();
        assertThat(sameTrace.stream().map(io.opentelemetry.sdk.trace.data.SpanData::getName))
                .anyMatch(n -> n.contains("getRunningSummary")).anyMatch(n -> n.equals("simple query"))
                .anyMatch(n -> n.contains("chat_client"));
        for (var span : sameTrace) System.out.println("SYNTHETIC_MULTI_SPAN name=" + span.getName()
                + " id=" + span.getSpanId() + " parent=" + span.getParentSpanId());
        assertThat(sameTrace).allSatisfy(span -> assertThat(span.getAttributes().toString() + span.getEvents())
                .doesNotContain("synthetic-secret-multi-question", "synthetic-secret-answer", "synthetic-secret-book-evidence", "synthetic-key"));

    }

    @Test void unknownSpecialistToolNamesCannotLeakModelContentToLogs(org.springframework.boot.test.system.CapturedOutput output) {
        assertThatThrownBy(() -> multiAgent.execute(new cn.watsonzhu.runagent.model.MultiAgentRequest(
                "unknown-tool-name-case", java.time.LocalDate.parse("2026-01-01"), java.time.LocalDate.parse("2026-01-31"), null)))
                .isInstanceOf(cn.watsonzhu.runagent.exception.MultiAgentException.class);
        assertThat(output.getAll()).doesNotContain("synthetic-secret-unknown-tool");
        provider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(exporter.getFinishedSpanItems()).allSatisfy(span -> assertThat(span.getAttributes().toString() + span.getEvents())
                .doesNotContain("synthetic-secret-unknown-tool"));
    }

    @TestConfiguration static class Exporter {
        @Bean InMemorySpanExporter spanExporter() { return InMemorySpanExporter.create(); }
        @Bean @org.springframework.context.annotation.Primary
        cn.watsonzhu.runagent.rag.KnowledgeSearchService syntheticSearch(io.micrometer.observation.ObservationRegistry registry, RunAgentObservation observations) {
            var store = org.springframework.ai.vectorstore.SimpleVectorStore.builder(new EvaluationFixtures.SyntheticEmbedding())
                .observationRegistry(registry).customObservationConvention(new PrivacySafeVectorStoreConvention()).build();
            store.add(List.of(new org.springframework.ai.document.Document("轻松 synthetic-secret-book-evidence", java.util.Map.of("sourceTitle", "Synthetic Book"))));
            var index = org.mockito.Mockito.mock(cn.watsonzhu.runagent.rag.KnowledgeIndexService.class);
            org.mockito.Mockito.when(index.store()).thenReturn(store);
            org.mockito.Mockito.when(index.isAvailable()).thenReturn(true);
            var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory(); beans.registerSingleton("syntheticIndex", index);
            var properties = new cn.watsonzhu.runagent.config.RagProperties(true, "", "unused-synthetic.json", false, 800, 350, 10, 100, 5, 0.5, java.time.Duration.ofSeconds(5));
            return new cn.watsonzhu.runagent.rag.KnowledgeSearchService(properties, beans.getBeanProvider(cn.watsonzhu.runagent.rag.KnowledgeIndexService.class), observations);
        }
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int status = request.contains("provider-failure") ? 500 : 200;
                String message = (request.contains("\"role\":\"tool\"") || request.contains("training analysis workflow")) ? "{\"role\":\"assistant\",\"content\":\"synthetic-secret-answer\"}"
                    : "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"getRunningSummary\",\"arguments\":\"{\\\"startDate\\\":\\\"2026-01-01\\\",\\\"endDate\\\":\\\"2026-01-31\\\"}\"}}]}";
                var json = new tools.jackson.databind.json.JsonMapper();
                var tree = json.readTree(request);
                String system = tree.path("messages").get(0).path("content").asText();
                if (system.contains("RunAgent supervisor")) {
                    if (system.contains("final RunAgent supervisor"))
                        message = json.writeValueAsString(java.util.Map.of("role", "assistant", "content", "synthetic-secret-answer"));
                    else {
                        String next = request.contains("KNOWLEDGE_COACH = SUCCESS") ? "FINISH"
                                : request.contains("RUNNING_ANALYST = SUCCESS") ? "KNOWLEDGE_COACH" : "RUNNING_ANALYST";
                        message = json.writeValueAsString(java.util.Map.of("role", "assistant", "content",
                                json.writeValueAsString(java.util.Map.of("nextAgent", next, "task", "轻松 synthetic-secret-subtask"))));
                    }
                } else if (system.contains("running-training knowledge specialist"))
                    message = json.writeValueAsString(java.util.Map.of("role", "assistant", "content", "synthetic-secret-answer"));
                if (system.contains("personal running-data analyst") && request.contains("unknown-tool-name-case"))
                    message = "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"bad\",\"type\":\"function\",\"function\":{\"name\":\"synthetic-secret-unknown-tool\",\"arguments\":\"{}\"}}]}";
                String body = status == 500 ? "{\"error\":{\"message\":\"synthetic-secret-provider-payload\",\"type\":\"server_error\"}}"
                    : "{\"id\":\"synthetic\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"synthetic-gateway\",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15},\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"stop\"}]}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start(); return server;
        } catch (Exception e) { throw new IllegalStateException("Synthetic gateway failed"); }
    }
}
