package cn.watsonzhu.runagent.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import cn.watsonzhu.runagent.config.RagProperties;
import cn.watsonzhu.runagent.rag.KnowledgeDocumentLoader;
import cn.watsonzhu.runagent.rag.KnowledgeIndexService;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.ollama.management.PullModelStrategy;
import org.springframework.ai.vectorstore.SearchRequest;
import static cn.watsonzhu.runagent.evaluation.EvaluationModels.*;

public final class LocalRagEvaluation {
    private LocalRagEvaluation() { }
    public record GoldenCase(String id, String question, Set<String> relevantDocumentIds, String expectedSourceTitle,
                             String expectedChapter, String expectedPage, int k) {
        public GoldenCase {
            safeId(id);
            relevantDocumentIds = relevantDocumentIds == null ? Set.of() : Set.copyOf(relevantDocumentIds);
            if (question == null || question.isBlank() || question.length() > 4000 || k < 1 || k > 100
                || relevantDocumentIds.isEmpty() && (expectedSourceTitle == null || expectedSourceTitle.isBlank()))
                throw new IllegalArgumentException("Invalid human labels");
        }
    }
    public static List<CaseResult> runIfPresent(Path golden, Map<String, String> environment) throws Exception {
        if (golden == null || !Files.isRegularFile(golden)) return List.of();
        var cases = new ArrayList<GoldenCase>();
        try {
            var root = EvaluationFixtures.JSON.readTree(golden.toFile());
            safeId(root.path("datasetVersion").asText());
            var ids = new java.util.HashSet<String>();
            for (var entry : root.path("cases")) {
                var item = EvaluationFixtures.JSON.treeToValue(entry, GoldenCase.class);
                if (!ids.add(item.id())) throw new IllegalArgumentException("Duplicate labels");
                cases.add(item);
            }
            if (cases.isEmpty()) throw new IllegalArgumentException("No human labels");
        } catch (RuntimeException e) {
            return List.of(EvaluationRunner.failure("local-golden-invalid", Category.LOCAL_PRIVATE_DATASET, Evidence.LOCAL_PRIVATE_DATASET, Reason.INVALID_LABELS));
        }
        try {
            return LiveEvaluationRunner.bounded(Duration.ofSeconds(60), () -> {
                String endpoint = environment.getOrDefault("RAG_OLLAMA_BASE_URL", "http://localhost:11434");
                var uri = java.net.URI.create(endpoint);
                if (!Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()) || !"http".equals(uri.getScheme()) || uri.getUserInfo() != null)
                    throw new IllegalArgumentException("Private evaluation requires local embedding");
                String model = environment.getOrDefault("RAG_EMBEDDING_MODEL", "bge-m3");
                var embedding = OllamaEmbeddingModel.builder().ollamaApi(OllamaApi.builder().baseUrl(endpoint).build())
                    .options(OllamaEmbeddingOptions.builder().model(model).build())
                    .modelManagementOptions(ModelManagementOptions.builder().pullModelStrategy(PullModelStrategy.NEVER).build()).build();
                var p = new RagProperties(true, "", environment.getOrDefault("RAG_VECTOR_STORE_PATH", ".run-agent/rag/vector-store.json"), false,
                    integer(environment, "RAG_CHUNK_SIZE", 800), integer(environment, "RAG_MIN_CHUNK_SIZE_CHARS", 350),
                    integer(environment, "RAG_MIN_CHUNK_LENGTH_TO_EMBED", 10), integer(environment, "RAG_MAX_NUM_CHUNKS", 5000),
                    integer(environment, "RAG_TOP_K", 5), Double.parseDouble(environment.getOrDefault("RAG_SIMILARITY_THRESHOLD", "0.5")), Duration.ofSeconds(60));
                // Load only; missing index/source never causes an automatic rebuild of private books.
                if (!Files.isRegularFile(Path.of(p.vectorStorePath()))) throw new IllegalStateException("Local index unavailable");
                var index = new KnowledgeIndexService(p, embedding, new KnowledgeDocumentLoader(), model, endpoint, ObservationRegistry.NOOP);
                index.initialize();
                var results = new ArrayList<CaseResult>();
                for (var item : cases) {
                    var documents = index.store().similaritySearch(SearchRequest.builder().query(item.question()).topK(item.k()).similarityThreshold(p.similarityThreshold()).build());
                    results.add(score(item, documents));
                }
                return List.copyOf(results);
            });
        } catch (Exception e) {
            return cases.stream().map(item -> EvaluationRunner.failure(item.id(), Category.LOCAL_PRIVATE_DATASET, Evidence.LOCAL_PRIVATE_DATASET, Reason.LOCAL_RETRIEVAL_UNAVAILABLE)).toList();
        }
    }
    public static CaseResult score(GoldenCase item, List<Document> documents) {
        Map<String, Double> metrics;
        boolean pass;
        if (!item.relevantDocumentIds().isEmpty()) {
            var score = EvaluationMetrics.retrieval(item.relevantDocumentIds(), documents.stream().map(Document::getId).toList(), item.k());
            pass = score.recall() == 1.0;
            metrics = Map.of("localRecallAtK", score.recall(), "localPrecisionAtK", score.precision(), "localMrr", score.reciprocalRank());
        } else {
            pass = documents.stream().limit(item.k()).anyMatch(doc -> item.expectedSourceTitle().equals(value(doc, "sourceTitle"))
                && (item.expectedChapter() == null || item.expectedChapter().equals(value(doc, "chapter")))
                && (item.expectedPage() == null || item.expectedPage().equals(value(doc, "pageNumber"))));
            metrics = Map.of("localSourceHit", pass ? 1.0 : 0);
        }
        return new CaseResult(item.id(), Category.LOCAL_PRIVATE_DATASET, Evidence.LOCAL_PRIVATE_DATASET, pass ? Status.PASS : Status.FAIL,
            metrics, pass ? Reason.OK : Reason.RETRIEVAL_BELOW_THRESHOLD);
    }
    public static DatasetIdentity identity(Path golden) throws Exception {
        byte[] bytes = Files.readAllBytes(golden);
        String version;
        try { version = safeId(EvaluationFixtures.JSON.readTree(bytes).path("datasetVersion").asText()); }
        catch (RuntimeException e) { version = "invalid"; }
        return new DatasetIdentity(version, EvaluationReportWriter.checksum(bytes));
    }
    private static String value(Document document, String key) { return String.valueOf(document.getMetadata().get(key)); }
    private static int integer(Map<String, String> environment, String name, int fallback) { return Integer.parseInt(environment.getOrDefault(name, Integer.toString(fallback))); }
}
