package cn.watsonzhu.runagent.model;

import java.util.List;

public record MultiAgentResponse(String executionId, List<String> delegationPath, List<String> agentsUsed,
                                 int delegationCount, String content) {
    public MultiAgentResponse {
        delegationPath = List.copyOf(delegationPath);
        agentsUsed = List.copyOf(agentsUsed);
    }
}
