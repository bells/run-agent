package cn.watsonzhu.runagent.reliability;

import java.util.List;
import cn.watsonzhu.runagent.multiagent.MultiAgentState;
import cn.watsonzhu.runagent.multiagent.Specialist;
import cn.watsonzhu.runagent.multiagent.SpecialistStatus;

/** Business evidence policy; it never creates another model call. */
public final class FallbackPolicy {
    private static final List<Specialist> SOURCES = List.of(Specialist.RUNNING_ANALYST,
            Specialist.KNOWLEDGE_COACH, Specialist.WEATHER_ADVISOR);
    public FallbackAction decide(MultiAgentState state) {
        if (!state.hasEvidence()) return FallbackAction.FAIL;
        return SOURCES.stream().anyMatch(agent -> failed(state, agent))
                ? FallbackAction.DEGRADE : FallbackAction.CONTINUE;
    }
    private static boolean failed(MultiAgentState state, Specialist agent) {
        return state.completedAgents().contains(agent)
                && (state.status(agent) == SpecialistStatus.FAILED || state.status(agent) == SpecialistStatus.UNAVAILABLE);
    }
    public String limitations(MultiAgentState state) {
        StringBuilder result = new StringBuilder();
        if (failed(state, Specialist.RUNNING_ANALYST))
            result.append("个人跑步分析本次不可用，建议仅作一般参考，未依据本次个人跑步证据。\n");
        if (failed(state, Specialist.KNOWLEDGE_COACH))
            result.append("训练知识分析本次不可用，未依据本次书籍分析证据。\n");
        if (failed(state, Specialist.WEATHER_ADVISOR))
            result.append("天气分析本次不可用，本次建议没有纳入实时天气。\n");
        return result.toString().strip();
    }
}
