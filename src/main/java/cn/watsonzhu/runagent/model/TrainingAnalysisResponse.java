package cn.watsonzhu.runagent.model;

import java.util.List;
import cn.watsonzhu.runagent.workflow.WorkflowMode;

public record TrainingAnalysisResponse(String executionId, WorkflowMode mode, List<String> executedNodes,
                                       String content) {
    public TrainingAnalysisResponse {
        executedNodes = List.copyOf(executedNodes);
    }
}
