package cn.watsonzhu.runagent.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class EvaluationRunnerTest {
    @TempDir Path directory;
    @Test void offlineRunnerExecutesActualToolsRetrievalAndAllGraphModes() throws Exception {
        var report = EvaluationRunner.run(directory, "0.8.0-SNAPSHOT", Map.of());
        assertThat(report.failedCases()).isZero();
        assertThat(report.caseCounts()).containsEntry("TOOL", 12).containsEntry("RAG", 4).containsEntry("WORKFLOW", 3);
        assertThat(report.metrics()).containsEntry("toolSelectionAccuracy", 1.0).containsEntry("toolArgumentAccuracy", 1.0)
            .containsEntry("workflowRouteAccuracy", 1.0).containsEntry("recallAtK", 1.0).containsEntry("mrr", 1.0);
        assertThat(report.metrics().get("precisionAtK")).isCloseTo(5.0 / 6, within(1e-9));
        assertThat(report.datasets()).hasSize(6);
        assertThat(Files.readString(directory.resolve("report.md"))).contains("SCRIPTED_MODEL_CONTRACT", "FAKE_EMBEDDING", "DETERMINISTIC_GRAPH");
    }
    @Test void retrievalAndCalculationExceptionsBecomeSafeFailedCases() throws Exception {
        var item = new EvaluationModels.RagCase("rag-fault", "synthetic", java.util.Set.of("a"), 2, false, 1, 1);
        var rag = EvaluationRunner.evaluateRetrieval(item, () -> { throw new IllegalStateException("synthetic-sensitive-error"); });
        assertThat(rag.status()).isEqualTo(EvaluationModels.Status.FAIL);
        assertThat(rag.metrics()).containsEntry("recallAtK", 0.0).containsEntry("precisionAtK", 0.0).containsEntry("mrr", 0.0);
        var calculation = EvaluationRunner.evaluateCalculation(() -> { throw new IllegalStateException("synthetic-sensitive-error"); });
        assertThat(calculation.status()).isEqualTo(EvaluationModels.Status.FAIL);
        var report = EvaluationReportWriter.report("0.8.0-SNAPSHOT", "OFFLINE", Map.of(), Map.of(), java.util.List.of(rag, calculation));
        EvaluationReportWriter.write(directory, report, null);
        assertThat(report.failedCases()).isEqualTo(2);
        assertThat(Files.readString(directory.resolve("report.json"))).contains("EXECUTION_FAILED").doesNotContain("synthetic-sensitive-error");
    }
    @Test void executionFaultsOverwritePreviousGreenReportBeforeFailingGate() throws Exception {
        EvaluationRunner.run(directory, "0.8.0-SNAPSHOT", Map.of());
        try (var fixture = org.mockito.Mockito.spy(new EvaluationFixtures())) {
            org.mockito.Mockito.doThrow(new IllegalStateException("synthetic-sensitive-error")).when(fixture).retrieve(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.doThrow(new IllegalStateException("synthetic-sensitive-error")).when(fixture).runningCalculation();
            assertThatThrownBy(() -> EvaluationRunner.run(directory, "0.8.0-SNAPSHOT", Map.of(), fixture))
                .isInstanceOf(IllegalStateException.class).hasMessage("Offline evaluation gate failed");
        }
        var json = new tools.jackson.databind.json.JsonMapper().readTree(directory.resolve("report.json").toFile());
        assertThat(json.path("failedCases").asInt()).isEqualTo(5);
        assertThat(json.path("metrics").path("recallAtK").asDouble()).isZero();
        assertThat(Files.readString(directory.resolve("report.json"))).doesNotContain("synthetic-sensitive-error");
        assertThat(Files.readString(directory.resolve("report.md"))).contains("EXECUTION_FAILED");
    }
    @Test void regressionGateWritesReportBeforeItFails() throws Exception {
        assertThatThrownBy(() -> EvaluationRunner.run(directory, "0.8.0-SNAPSHOT", Map.of("precisionAtK", 1.0)))
            .isInstanceOf(IllegalStateException.class).hasMessage("Offline evaluation gate failed");
        assertThat(Files.readString(directory.resolve("report.json"))).contains("precisionAtK");
    }
}
