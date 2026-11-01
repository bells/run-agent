package cn.watsonzhu.runagent.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import static org.assertj.core.api.Assertions.*;

class LocalRagEvaluationTest {
    @TempDir Path directory;
    @Test void absentGoldenDoesNotInitializeAnyPrivateDependency() throws Exception {
        assertThat(LocalRagEvaluation.runIfPresent(directory.resolve("absent.json"), Map.of())).isEmpty();
    }
    @Test void humanSourceLabelsAreNotMisrepresentedAsDocumentRecall() {
        var label = new LocalRagEvaluation.GoldenCase("private-case", "synthetic-private-question", Set.of(), "Synthetic Book", "Recovery", "12", 5);
        var result = LocalRagEvaluation.score(label, List.of(new Document("synthetic-private-evidence", Map.of("sourceTitle", "Synthetic Book", "chapter", "Recovery", "pageNumber", "12"))));
        assertThat(result.category()).isEqualTo(EvaluationModels.Category.LOCAL_PRIVATE_DATASET);
        assertThat(result.metrics()).containsEntry("localSourceHit", 1.0).doesNotContainKey("recallAtK");
        assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(result)).doesNotContain("synthetic-private-question", "synthetic-private-evidence", "Synthetic Book", "Recovery");
    }
    @Test void malformedOrUnlabelledGoldenFailsSafelyAndCannotCreateFalseTruth() throws Exception {
        Path golden = directory.resolve("golden.json");
        Files.writeString(golden, "{\"datasetVersion\":\"v1\",\"cases\":[{\"id\":\"local-case\",\"question\":\"private-secret\",\"k\":5}]}");
        var results = LocalRagEvaluation.runIfPresent(golden, Map.of());
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().reasonCode()).isEqualTo(EvaluationModels.Reason.INVALID_LABELS);
        assertThat(results.getFirst().metrics()).isEmpty();
    }
}
