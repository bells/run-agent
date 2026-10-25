package cn.watsonzhu.runagent.workflow.node;

import java.util.Map;
import cn.watsonzhu.runagent.exception.WorkflowException;
import cn.watsonzhu.runagent.workflow.TrainingAnalysisState;
import cn.watsonzhu.runagent.workflow.WorkflowMode;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

@Component
public class ValidateOutputNode implements NodeAction<TrainingAnalysisState> {
    @Override public Map<String, Object> apply(TrainingAnalysisState state) {
        if (state.analysis() == null || state.analysis().isBlank()
                || state.mode() != WorkflowMode.KNOWLEDGE_ONLY && state.runningSummary() == null
                || state.mode() != WorkflowMode.RUNNING_ONLY && state.knowledgeEvidence().isEmpty()) {
            throw new WorkflowException(WorkflowException.Kind.EXECUTION_FAILED);
        }
        return Map.of();
    }
}
