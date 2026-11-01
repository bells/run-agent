package cn.watsonzhu.runagent.evaluation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import cn.watsonzhu.runagent.agent.RunAgentService;
import cn.watsonzhu.runagent.config.*;
import cn.watsonzhu.runagent.prompt.PromptCatalog;
import cn.watsonzhu.runagent.rag.*;
import cn.watsonzhu.runagent.service.RunningDataService;
import cn.watsonzhu.runagent.tool.RunningTools;
import cn.watsonzhu.runagent.workflow.*;
import cn.watsonzhu.runagent.workflow.node.*;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static cn.watsonzhu.runagent.evaluation.EvaluationModels.*;

public final class EvaluationFixtures implements AutoCloseable {
    static final JsonMapper JSON = new JsonMapper();
    static final List<String> FILES = List.of("tool-cases.json", "rag-cases.json", "workflow-cases.json",
        "synthetic-knowledge.json", "synthetic-activities.json", "model-scripts.json");
    private final Map<String, JsonNode> datasets = new TreeMap<>();
    private final Map<String, DatasetIdentity> identities = new TreeMap<>();
    private final Path activities;
    public final RunningDataService running;
    public final KnowledgeSearchService search;
    public final PromptCatalog prompts;

    public EvaluationFixtures() throws Exception {
        for (String name : FILES) {
            byte[] bytes;
            try (var input = new ClassPathResource("evaluation/" + name).getInputStream()) { bytes = input.readAllBytes(); }
            var tree = JSON.readTree(bytes);
            identities.put(name, new DatasetIdentity(tree.path("datasetVersion").asText(), EvaluationReportWriter.checksum(bytes)));
            datasets.put(name, tree);
        }
        activities = Files.createTempFile("runagent-synthetic-", ".json");
        Files.writeString(activities, datasets.get("synthetic-activities.json").path("activities").toString());
        running = new RunningDataService(new RunningDataProperties(activities.toString()), JSON);
        var properties = new RagProperties(true, "", "unused-synthetic-index.json", false, 800, 350, 10, 5000, 2, 0.5, Duration.ofSeconds(5));
        var store = SimpleVectorStore.builder(new SyntheticEmbedding()).build();
        var documents = new ArrayList<Document>();
        for (var item : datasets.get("synthetic-knowledge.json").path("documents"))
            documents.add(Document.builder().id(item.path("id").asText()).text(item.path("text").asText())
                .metadata(Map.of("sourceTitle", item.path("sourceTitle").asText())).build());
        store.add(documents);
        // Keep real retrieval/ranking; replace only the persisted index holder.
        var index = mock(KnowledgeIndexService.class);
        when(index.store()).thenReturn(store);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("syntheticIndex", index);
        search = new KnowledgeSearchService(properties, beans.getBeanProvider(KnowledgeIndexService.class), cn.watsonzhu.runagent.observability.ObservationTestSupport.noop());
        prompts = new PromptCatalog(prompt("run-agent-system.txt"), prompt("running-intent-system.txt"), prompt("run-agent-agent-system.txt"));
    }
    private static ByteArrayResource prompt(String name) throws Exception {
        String content = new ClassPathResource("prompts/" + name).getContentAsString(StandardCharsets.UTF_8);
        return new ByteArrayResource(content.replace("{{CURRENT_DATE}}", "2026-04-01").getBytes(StandardCharsets.UTF_8));
    }
    public Map<String, DatasetIdentity> identities() { return Map.copyOf(identities); }
    public <T> List<T> cases(String name, Class<T> type) {
        var result = new ArrayList<T>();
        var seen = new java.util.HashSet<String>();
        for (var item : datasets.get(name).path("cases")) {
            String id = safeId(item.path("id").asText());
            if (!seen.add(id)) throw new IllegalArgumentException("Duplicate evaluation case id");
            result.add(JSON.treeToValue(item, type));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Empty evaluation dataset");
        return List.copyOf(result);
    }
    public List<ToolCall> script(String id) {
        for (var item : datasets.get("model-scripts.json").path("cases")) {
            if (id.equals(item.path("id").asText())) {
                var calls = new ArrayList<ToolCall>();
                for (var call : item.path("calls")) calls.add(JSON.treeToValue(call, ToolCall.class));
                return List.copyOf(calls);
            }
        }
        throw new IllegalArgumentException("Missing independent model script");
    }
    public RunAgentService agent(ChatModel model, List<ToolCall> recorded) {
        return agent(model, recorded, cn.watsonzhu.runagent.observability.ObservationTestSupport.noop());
    }
    public RunAgentService agent(ChatModel model, List<ToolCall> recorded, cn.watsonzhu.runagent.observability.RunAgentObservation observations) {
        var callbacks = java.util.Arrays.stream(ToolCallbacks.from(new RunningTools(running)))
            .map(callback -> new RecordingToolCallback(callback, recorded)).toArray(ToolCallback[]::new);
        var manager = ToolCallingManager.builder().maxCallsPerTool(4).maxTotalToolCalls(8).build();
        var builder = ChatClient.builder(model, ObservationRegistry.NOOP, null, null, ToolCallingAdvisor.builder().toolCallingManager(manager));
        return new RunAgentService(builder, prompts, () -> callbacks, MessageWindowChatMemory.builder().maxMessages(20).build(), observations);
    }
    public List<String> retrieve(RagCase item) {
        return search.retrieveDocuments("evaluation", item.question()).stream().map(Document::getId).toList();
    }
    public boolean runningCalculation() {
        // Independently hand-calculated values, unrelated to expected Tool paths.
        var summary = running.summarize(java.time.LocalDate.parse("2026-01-01"), java.time.LocalDate.parse("2026-01-31"));
        return summary.runCount() == 2 && summary.totalDistanceKm() == 15 && summary.totalDurationSeconds() == 5100
            && running.personalBest(cn.watsonzhu.runagent.model.running.DistanceType.FIVE_K).averagePaceSecondsPerKm() == 300;
    }
    public TrainingAnalysisWorkflow workflow() {
        return new TrainingAnalysisWorkflow(new ValidateInputNode(), new RunningDataNode(running::summarize), new KnowledgeRetrievalNode(search),
            new AnalysisNode(new ScriptedChatModel(List.of()), prompts, new TrainingAnalysisContextBuilder(), io.micrometer.observation.ObservationRegistry.NOOP), new ValidateOutputNode(), new WorkflowProperties(Duration.ofSeconds(5)), cn.watsonzhu.runagent.observability.ObservationTestSupport.noop());
    }
    @Override public void close() throws java.io.IOException { Files.deleteIfExists(activities); }

    /** Deliberately lexical test vectors; no labels or expected ranks are consulted. */
    public static final class SyntheticEmbedding implements EmbeddingModel {
        @Override public float[] embed(Document document) { return embed(document.getText()); }
        @Override public int dimensions() { return 5; }
        @Override public EmbeddingResponse call(EmbeddingRequest request) {
            var results = new ArrayList<Embedding>();
            for (String text : request.getInstructions()) {
                float[] vector = new float[5];
                vector[text.contains("轻松") ? 0 : text.contains("阈值") ? 1 : text.contains("长距离") ? 2 : text.contains("厨房") ? 3 : 4] = 1;
                results.add(new Embedding(vector, results.size()));
            }
            return new EmbeddingResponse(results);
        }
    }
}
