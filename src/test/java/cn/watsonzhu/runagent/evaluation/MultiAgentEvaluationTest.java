package cn.watsonzhu.runagent.evaluation;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MultiAgentEvaluationTest {
    @Test void delegationScoringRejectsMissingExtraAndRepeatedAgents() {
        assertThat(MultiAgentEvaluation.matches(List.of("RUNNING_ANALYST", "KNOWLEDGE_COACH"), List.of("KNOWLEDGE_COACH", "RUNNING_ANALYST"), false)).isTrue();
        assertThat(MultiAgentEvaluation.matches(List.of("RUNNING_ANALYST", "KNOWLEDGE_COACH"), List.of("KNOWLEDGE_COACH", "RUNNING_ANALYST"), true)).isFalse();
        assertThat(MultiAgentEvaluation.matches(List.of("RUNNING_ANALYST"), List.of("RUNNING_ANALYST", "WEATHER_ADVISOR"), false)).isFalse();
        assertThat(MultiAgentEvaluation.matches(List.of("RUNNING_ANALYST"), List.of("RUNNING_ANALYST", "RUNNING_ANALYST"), false)).isFalse();
        assertThat(MultiAgentEvaluation.matches(List.of("RUNNING_ANALYST", "KNOWLEDGE_COACH"), List.of("RUNNING_ANALYST"), false)).isFalse();
    }
}
