package cn.watsonzhu.runagent.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class EvaluationModels {
    private EvaluationModels() { }
    public enum Category { TOOL, RAG, WORKFLOW, RUNNING_CALCULATION, JUDGE, LOCAL_PRIVATE_DATASET }
    public enum Status { PASS, FAIL, SKIPPED, UNJUDGED }
    public enum Evidence { SCRIPTED_MODEL_CONTRACT, FAKE_EMBEDDING, DETERMINISTIC_GRAPH, LIVE_MODEL, LLM_JUDGE, LOCAL_PRIVATE_DATASET }
    public enum Reason { OK, TOOL_MISMATCH, ARGUMENT_MISMATCH, RETRIEVAL_BELOW_THRESHOLD, ROUTE_MISMATCH, UNJUDGED_LABELS,
        NO_CONTEXT, EXECUTION_FAILED, TIMEOUT, JUDGE_FAILED, LOCAL_RETRIEVAL_UNAVAILABLE, INVALID_LABELS }
    public record ToolCall(String name, JsonNode arguments) {
        public ToolCall {
            safeId(name);
            if (arguments == null || !arguments.isObject()) throw new IllegalArgumentException("Tool arguments must be an object");
            arguments = arguments.deepCopy();
        }
    }
    public record ToolCase(String id, String question, boolean ordered, List<List<ToolCall>> allowedToolSets) {
        public ToolCase {
            safeId(id);
            if (question == null || question.isBlank() || allowedToolSets == null || allowedToolSets.isEmpty())
                throw new IllegalArgumentException("Invalid tool case");
            allowedToolSets = allowedToolSets.stream().map(List::copyOf).toList();
        }
    }
    public record ToolScore(boolean selection, boolean arguments) { }
    public record RetrievalScore(boolean judged, double recall, double precision, double reciprocalRank) { }
    public record RagCase(String id, String question, Set<String> relevantDocumentIds, int k,
                          boolean expectedNoContext, double minRecall, double minReciprocalRank) { }
    public record WorkflowCase(String id, String mode, String question, String startDate, String endDate, List<String> expectedNodes) { }
    public record CaseResult(String caseId, Category category, Evidence evidence, Status status,
                             Map<String, Double> metrics, Reason reasonCode) {
        public CaseResult {
            safeId(caseId);
            if (category == null || evidence == null || status == null || reasonCode == null) throw new IllegalArgumentException("Missing result metadata");
            metrics.forEach((key, value) -> {
                safeId(key);
                if (value == null || !Double.isFinite(value)) throw new IllegalArgumentException("Invalid metric");
            });
            metrics = Map.copyOf(metrics);
        }
    }
    public record DatasetIdentity(String datasetVersion, String sha256) {
        public DatasetIdentity {
            safeId(datasetVersion);
            if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid checksum");
        }
    }
    public record Report(String runTimestamp, String applicationVersion, String executionType, String metricDefinitionVersion,
                         Map<String, DatasetIdentity> datasets, String datasetChecksum, Map<String, String> model,
                         Map<String, Integer> caseCounts, Map<String, Double> metrics,
                         int passedCases, int failedCases, int skippedCases, int unjudgedCases, List<CaseResult> cases) { }
    public static String safeId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) throw new IllegalArgumentException("Invalid evaluation identifier");
        return value;
    }
}
