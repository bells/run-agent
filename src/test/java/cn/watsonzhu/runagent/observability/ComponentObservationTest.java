package cn.watsonzhu.runagent.observability;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import cn.watsonzhu.runagent.config.*;
import cn.watsonzhu.runagent.evaluation.*;
import cn.watsonzhu.runagent.rag.*;
import cn.watsonzhu.runagent.workflow.*;
import cn.watsonzhu.runagent.workflow.node.*;
import cn.watsonzhu.runagent.model.TrainingAnalysisRequest;
import cn.watsonzhu.runagent.exception.WorkflowException;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.*;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComponentObservationTest {
    @TempDir Path directory;
    @Test void persistedVectorStoreActuallyObservesQueriesWithoutExportingTheirText() throws Exception {
        var registry = TestObservationRegistry.create();
        var captured = capture(registry);
        Path source = directory.resolve("synthetic.txt");
        Files.writeString(source, "轻松跑应采用舒适强度，允许恢复。".repeat(40));
        var p = new RagProperties(true, source.toString(), directory.resolve("index.json").toString(), true, 800, 20, 1, 100, 5, 0.5, Duration.ofSeconds(5));
        var index = new KnowledgeIndexService(p, new EvaluationFixtures.SyntheticEmbedding(), new KnowledgeDocumentLoader(), "synthetic", "local", registry);
        index.initialize();
        index.store().similaritySearch(SearchRequest.builder().query("轻松 synthetic-secret-query").topK(5).build());
        registry.assertThat().hasObservationWithNameEqualTo("db.vector.client.operation");
        assertThat(captured).allSatisfy(context -> assertThat(exported(context)).doesNotContain("synthetic-secret-query", "舒适强度"));
        var loaded = new KnowledgeIndexService(new RagProperties(true, "", p.vectorStorePath(), false, 800, 20, 1, 100, 5, 0.5, Duration.ofSeconds(5)),
            new EvaluationFixtures.SyntheticEmbedding(), new KnowledgeDocumentLoader(), "synthetic", "local", registry);
        loaded.initialize();
        assertThat(loaded.store().similaritySearch("轻松")).isNotEmpty();
    }
    @Test void combinedWorkflowProducesFiveNodeObservationsAndIsolatedAnalysisClient() throws Exception {
        var registry = TestObservationRegistry.create();
        var captured = capture(registry);
        var meters = new SimpleMeterRegistry();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        var observations = new RunAgentObservation(registry, meters);
        try (var fixture = new EvaluationFixtures()) {
            ChatModel model = prompt -> {
                assertThat(prompt.getInstructions()).hasSize(2);
                if (prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options) {
                    assertThat(options.getToolCallbacks()).isNullOrEmpty();
                    assertThat(options.getToolContext()).isNullOrEmpty();
                }
                return new ScriptedChatModel(List.of()).call(prompt);
            };
            var workflow = workflow(fixture, model, fixture.running::summarize, registry, observations);
            var response = workflow.execute(request());
            assertThat(response.executedNodes()).hasSize(5);
            registry.assertThat().hasObservationWithNameEqualTo("spring.ai.chat.client");
            var nodes = captured.stream().filter(c -> c.getName().equals("runagent.workflow.node")).toList();
            assertThat(nodes).hasSize(5);
            assertThat(nodes.stream().map(c -> c.getLowCardinalityKeyValue("node").getValue())).containsExactly("validateInput", "fetchRunningData", "retrieveKnowledge", "analyze", "validateOutput");
            assertThat(meters.get("runagent.workflow").tag("mode", "COMBINED").timer().count()).isEqualTo(1);
            assertThat(captured).allSatisfy(c -> assertThat(exported(c)).doesNotContain("synthetic-secret"));
            captured.clear();
            var failed = workflow(fixture, model, (s, e) -> { throw new IllegalStateException("synthetic-secret"); }, registry, observations);
            assertThatThrownBy(() -> failed.execute(request())).isInstanceOf(WorkflowException.class);
            assertThat(captured.stream().filter(c -> c.getName().equals("runagent.workflow.node")).map(c -> c.getLowCardinalityKeyValue("node").getValue())).containsExactly("validateInput", "fetchRunningData");
            assertThat(meters.getMeters()).allSatisfy(m -> assertThat(m.getId().getTags().toString()).doesNotContain(response.executionId(), "question", "queryId", "conversationId"));
        }
    }
    @Test void actualAgentThreeToolCallsProduceOneSummarySample() throws Exception {
        var registry = TestObservationRegistry.create();
        var meters = new SimpleMeterRegistry();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        try (var fixture = new EvaluationFixtures()) {
            var recorded = new java.util.concurrent.CopyOnWriteArrayList<EvaluationModels.ToolCall>();
            var response = fixture.agent(new ScriptedChatModel(fixture.script("summary-and-recent")), recorded,
                new RunAgentObservation(registry, meters)).agent(null, "synthetic comparison");
            assertThat(response.toolCallCount()).isEqualTo(3);
            assertThat(recorded).hasSize(3);
            assertThat(meters.get("runagent.agent.tool.calls").summary().count()).isEqualTo(1);
            assertThat(meters.get("runagent.agent.tool.calls").summary().totalAmount()).isEqualTo(3);
            registry.assertThat().hasObservationWithNameEqualTo("runagent.agent");
        }
    }
    @Test void retrievalRecordsMatchDistributionAndNoContextAndUnavailableOutcomes() {
        var meters = new SimpleMeterRegistry();
        var observations = new RunAgentObservation(ObservationRegistry.NOOP, meters);
        var store = SimpleVectorStore.builder(new EvaluationFixtures.SyntheticEmbedding()).build();
        store.add(List.of(new Document("轻松一"), new Document("轻松二"), new Document("轻松三")));
        var index = mock(KnowledgeIndexService.class);
        when(index.store()).thenReturn(store);
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("index", index);
        var p = new RagProperties(true, "", "unused.json", false, 800, 350, 10, 100, 5, 0.5, Duration.ofSeconds(5));
        var search = new KnowledgeSearchService(p, beans.getBeanProvider(KnowledgeIndexService.class), observations);
        assertThat(search.retrieveDocuments("dynamic-query-id", "轻松")).hasSize(3);
        assertThat(search.retrieveDocuments("dynamic-query-id", "量子")).isEmpty();
        var matches = meters.get("runagent.rag.matches").summary();
        assertThat(matches.count()).isEqualTo(2);
        assertThat(matches.totalAmount()).isEqualTo(3);
        assertThat(meters.get("runagent.rag.requests").tag("outcome", "no_context").counter().count()).isEqualTo(1);
        when(index.store()).thenThrow(new cn.watsonzhu.runagent.exception.KnowledgeBaseUnavailableException("private-index"));
        assertThatThrownBy(() -> search.retrieveDocuments("id", "轻松")).isInstanceOf(cn.watsonzhu.runagent.exception.KnowledgeBaseUnavailableException.class);
        assertThat(meters.get("runagent.rag.requests").tag("outcome", "unavailable").counter().count()).isEqualTo(1);
        assertThat(meters.getMeters()).allSatisfy(m -> assertThat(m.getId().getTags().toString()).doesNotContain("dynamic-query-id", "轻松", "question", "queryId"));
    }
    private TrainingAnalysisWorkflow workflow(EvaluationFixtures fixture, ChatModel model, RunningCapabilityGateway gateway, ObservationRegistry registry, RunAgentObservation observations) {
        return new TrainingAnalysisWorkflow(new ValidateInputNode(), new RunningDataNode(gateway), new KnowledgeRetrievalNode(fixture.search),
            new AnalysisNode(model, fixture.prompts, new TrainingAnalysisContextBuilder(), registry), new ValidateOutputNode(), new WorkflowProperties(Duration.ofSeconds(5)), observations);
    }
    private TrainingAnalysisRequest request() { return new TrainingAnalysisRequest("轻松 synthetic-secret", WorkflowMode.COMBINED, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31")); }
    private List<Observation.Context> capture(ObservationRegistry registry) {
        var contexts = new ArrayList<Observation.Context>();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override public boolean supportsContext(Observation.Context c) { return true; }
            @Override public void onStop(Observation.Context c) { contexts.add(c); }
        });
        return contexts;
    }
    private String exported(Observation.Context c) {
        return c.getLowCardinalityKeyValues().toString() + c.getHighCardinalityKeyValues() + (c.getError() == null ? "" : c.getError().getMessage());
    }
}
