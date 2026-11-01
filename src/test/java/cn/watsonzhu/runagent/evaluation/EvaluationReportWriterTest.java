package cn.watsonzhu.runagent.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static cn.watsonzhu.runagent.evaluation.EvaluationModels.*;

class EvaluationReportWriterTest {
    @TempDir Path directory;
    @Test void safeReportAggregatesCasesAndProducesValidJsonAndMarkdown() throws Exception {
        var report = EvaluationReportWriter.report("0.8.0-SNAPSHOT", "OFFLINE", datasets(), Map.of(), List.of(
            result("a", Category.TOOL, Map.of("toolSelectionAccuracy", 1.0, "toolArgumentAccuracy", 0.0), Status.FAIL),
            result("b", Category.TOOL, Map.of("toolSelectionAccuracy", 1.0, "toolArgumentAccuracy", 1.0), Status.PASS),
            result("rag", Category.RAG, Map.of("recallAtK", 0.5), Status.PASS),
            result("unjudged", Category.RAG, Map.of(), Status.UNJUDGED)));
        EvaluationReportWriter.write(directory, report, null);
        var json = new JsonMapper().readTree(directory.resolve("report.json").toFile());
        assertThat(json.path("metrics").path("toolArgumentAccuracy").asDouble()).isEqualTo(0.5);
        assertThat(json.path("passedCases").asInt()).isEqualTo(2);
        assertThat(json.path("failedCases").asInt()).isEqualTo(1);
        assertThat(json.path("unjudgedCases").asInt()).isEqualTo(1);
        assertThat(json.path("caseCounts").path("TOOL").asInt()).isEqualTo(2);
        assertThat(json.path("datasetChecksum").asText()).matches("[a-f0-9]{64}");
        assertThat(Files.readString(directory.resolve("report.md"))).contains("toolArgumentAccuracy", "0.5000", "OFFLINE");
        assertThat(Files.readString(directory.resolve("report.json"))).doesNotContain("question", "arguments", "synthetic-secret", "apiKey");
    }
    @Test void baselineRequiresSameDatasetExecutionTypeAndMetricDefinition() throws Exception {
        var current = EvaluationReportWriter.report("0.8.0-SNAPSHOT", "OFFLINE", datasets(), Map.of(), List.of(
            result("a", Category.TOOL, Map.of("toolSelectionAccuracy", 1.0), Status.PASS)));
        var baseline = directory.resolve("baseline.json");
        var old = EvaluationReportWriter.report("0.8.0-SNAPSHOT", "LIVE", datasets(), Map.of(), current.cases());
        new JsonMapper().writeValue(baseline.toFile(), old);
        EvaluationReportWriter.write(directory, current, baseline);
        assertThat(Files.readString(directory.resolve("report.md"))).contains("BASELINE_INCOMPATIBLE");
        new JsonMapper().writeValue(baseline.toFile(), current);
        EvaluationReportWriter.write(directory, current, baseline);
        assertThat(Files.readString(directory.resolve("report.md"))).contains("delta=0.0000");
    }
    @Test void unsafeIdentifiersAndModelMetadataCannotEnterArtifacts() {
        assertThatThrownBy(() -> result("secret\nquestion", Category.TOOL, Map.of(), Status.PASS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvaluationReportWriter.report("0.8.0-SNAPSHOT", "LIVE", datasets(),
            Map.of("apiKey", "synthetic-secret"), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvaluationMetrics.threshold("NaN")).isInstanceOf(IllegalArgumentException.class);
    }
    private Map<String, DatasetIdentity> datasets() { return Map.of("tools", new DatasetIdentity("v1", "a".repeat(64))); }
    private CaseResult result(String id, Category category, Map<String, Double> metrics, Status status) {
        return new CaseResult(id, category, Evidence.SCRIPTED_MODEL_CONTRACT, status, metrics, Reason.OK);
    }
}
