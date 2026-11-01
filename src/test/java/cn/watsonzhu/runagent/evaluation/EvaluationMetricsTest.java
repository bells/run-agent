package cn.watsonzhu.runagent.evaluation;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static cn.watsonzhu.runagent.evaluation.EvaluationModels.*;

class EvaluationMetricsTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static ToolCall call(String name, String arguments) { return new ToolCall(name, JSON.readTree(arguments)); }

    @Test void unorderedMatchingPreservesRepeatedCallsAndChecksArgumentsSemantically() {
        var a = call("getRunningSummary", "{\"startDate\":\"2026-01-01\",\"endDate\":\"2026-01-31\"}");
        var b = call("getRunningSummary", "{\"startDate\":\"2026-02-01\",\"endDate\":\"2026-02-28\"}");
        var c = new ToolCase("comparison", "synthetic", false, List.of(List.of(a, b)));
        assertThat(EvaluationMetrics.tools(c, List.of(b, a))).isEqualTo(new ToolScore(true, true));
        assertThat(EvaluationMetrics.tools(c, List.of(a, a))).isEqualTo(new ToolScore(true, false));
        assertThat(EvaluationMetrics.tools(c, List.of(a))).isEqualTo(new ToolScore(false, false));
        assertThat(EvaluationMetrics.tools(c, List.of(a, b, a))).isEqualTo(new ToolScore(false, false));
    }

    @Test void alternativesOrderedCallsAndNoToolAreDistinctContracts() {
        var a = call("getRunningSummary", "{}");
        var b = call("getRecentRuns", "{\"limit\":5}");
        var c = new ToolCase("alternative", "synthetic", true, List.of(List.of(a, b), List.of(b)));
        assertThat(EvaluationMetrics.tools(c, List.of(b))).isEqualTo(new ToolScore(true, true));
        assertThat(EvaluationMetrics.tools(c, List.of(b, a))).isEqualTo(new ToolScore(false, false));
        var none = new ToolCase("no-tool", "什么是节奏跑？", false, List.of(List.of()));
        assertThat(EvaluationMetrics.tools(none, List.of())).isEqualTo(new ToolScore(true, true));
        assertThat(EvaluationMetrics.tools(none, List.of(a))).isEqualTo(new ToolScore(false, false));
    }

    @Test void jsonFieldOrderAndNumericRepresentationDoNotChangeArgumentValue() {
        var c = new ToolCase("numeric", "synthetic", false, List.of(List.of(call("tool", "{\"limit\":5,\"date\":\"2026-01-01\"}"))));
        assertThat(EvaluationMetrics.tools(c, List.of(call("tool", "{\"date\":\"2026-01-01\",\"limit\":5.0}"))).arguments()).isTrue();
        assertThat(EvaluationMetrics.tools(c, List.of(call("tool", "{\"date\":\"2026-01-01\",\"limit\":\"5\"}"))).arguments()).isFalse();
        assertThat(EvaluationMetrics.tools(c, List.of(call("tool", "{\"limit\":5}"))).arguments()).isFalse();
        assertThat(EvaluationMetrics.tools(c, List.of(call("tool", "{\"limit\":5,\"date\":\"2026-02-30\"}"))).arguments()).isFalse();
    }

    @ParameterizedTest @CsvSource({"1,1.0", "2,0.5", "5,0.2"})
    void firstRelevantRankControlsReciprocalRank(int rank, double expected) {
        var ranked = new java.util.ArrayList<String>();
        for (int i = 1; i < rank; i++) ranked.add("noise-" + i);
        ranked.add("relevant");
        assertThat(EvaluationMetrics.retrieval(Set.of("relevant"), ranked, 5).reciprocalRank()).isEqualTo(expected);
    }

    @Test void fixedKDenominatorEmptyResultsAndUnjudgedLabels() {
        var score = EvaluationMetrics.retrieval(Set.of("a", "b", "c"), List.of("a", "x", "c"), 5);
        assertThat(score.recall()).isCloseTo(2.0 / 3, within(1e-9));
        assertThat(score.precision()).isEqualTo(0.4);
        assertThat(score.reciprocalRank()).isEqualTo(1);
        assertThat(EvaluationMetrics.retrieval(Set.of("a"), List.of(), 5)).isEqualTo(new RetrievalScore(true, 0, 0, 0));
        assertThat(EvaluationMetrics.retrieval(Set.of(), List.of("x"), 5).judged()).isFalse();
        assertThatThrownBy(() -> EvaluationMetrics.retrieval(Set.of("a"), List.of(), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void duplicateIdsNeverInflateHitsOrImproveOriginalRank() {
        var score = EvaluationMetrics.retrieval(Set.of("a", "b"), List.of("x", "x", "a", "a", "b"), 5);
        assertThat(score.recall()).isEqualTo(1);
        assertThat(score.precision()).isEqualTo(0.4);
        assertThat(score.reciprocalRank()).isCloseTo(1.0 / 3, within(1e-9));
    }

    @Test void workflowRouteRequiresExactOrderAndLength() {
        assertThat(EvaluationMetrics.route(List.of("validateInput", "analyze"), List.of("validateInput", "analyze"))).isTrue();
        assertThat(EvaluationMetrics.route(List.of("validateInput", "analyze"), List.of("analyze", "validateInput"))).isFalse();
        assertThat(EvaluationMetrics.route(List.of("validateInput", "analyze"), List.of("validateInput"))).isFalse();
    }
}
