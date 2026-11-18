package cn.watsonzhu.runagent.reliability;

import java.util.List;
import cn.watsonzhu.runagent.model.MultiAgentRequest;
import cn.watsonzhu.runagent.multiagent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FallbackPolicyTest {
    private final FallbackPolicy policy = new FallbackPolicy();
    @Test void noEvidenceFailsSafe() {
        var state = state();
        assertThat(policy.decide(state)).isEqualTo(FallbackAction.FAIL);
    }
    @Test void partialEvidenceHasExplicitFactBoundaries() {
        var state = state();
        state = new MultiAgentState(state.mergeWith(java.util.Map.of("completedAgents", List.of(Specialist.RUNNING_ANALYST,Specialist.KNOWLEDGE_COACH,Specialist.WEATHER_ADVISOR),
                "runningStatus",SpecialistStatus.UNAVAILABLE,"weatherStatus",SpecialistStatus.FAILED,"knowledgeStatus", SpecialistStatus.SUCCESS), MultiAgentState.SCHEMA));
        assertThat(policy.decide(state)).isEqualTo(FallbackAction.DEGRADE);
        assertThat(policy.limitations(state)).contains("个人跑步分析本次不可用", "天气分析本次不可用");
        assertThat(policy.limitations(state)).doesNotContain("训练知识分析本次不可用");
    }
    @Test void completeEvidenceContinuesWithoutLimitations() {
        var state = state();
        state = new MultiAgentState(state.mergeWith(java.util.Map.of("completedAgents", List.of(Specialist.RUNNING_ANALYST, Specialist.KNOWLEDGE_COACH, Specialist.WEATHER_ADVISOR),
                "runningStatus", SpecialistStatus.SUCCESS,"knowledgeStatus", SpecialistStatus.SUCCESS,"weatherStatus", SpecialistStatus.SUCCESS), MultiAgentState.SCHEMA));
        assertThat(policy.decide(state)).isEqualTo(FallbackAction.CONTINUE);
        assertThat(policy.limitations(state)).isEmpty();
    }
    @Test void intentionallyUnselectedSourcesAreNotFailures() {
        var state = state();
        state = new MultiAgentState(state.mergeWith(java.util.Map.of("completedAgents",List.of(Specialist.RUNNING_ANALYST),
                "runningStatus",SpecialistStatus.SUCCESS),MultiAgentState.SCHEMA));
        assertThat(policy.decide(state)).isEqualTo(FallbackAction.CONTINUE);
        assertThat(policy.limitations(state)).isEmpty();
    }
    @Test void unavailableReportDoesNotClaimRetrievalOrIndexFailed() {
        var state=state();
        state=new MultiAgentState(state.mergeWith(java.util.Map.of("completedAgents",List.of(Specialist.RUNNING_ANALYST,Specialist.KNOWLEDGE_COACH),
            "runningStatus",SpecialistStatus.SUCCESS,"knowledgeStatus",SpecialistStatus.FAILED),MultiAgentState.SCHEMA));
        assertThat(policy.limitations(state)).contains("训练知识分析本次不可用").doesNotContain("知识库本次不可用","索引失败","检索失败");
    }
    private MultiAgentState state() {
        return new MultiAgentState(MultiAgentState.initial(new MultiAgentRequest("synthetic",null,null,"synthetic city"),
                "synthetic-id", List.of(Specialist.RUNNING_ANALYST, Specialist.KNOWLEDGE_COACH, Specialist.WEATHER_ADVISOR)));
    }
}
