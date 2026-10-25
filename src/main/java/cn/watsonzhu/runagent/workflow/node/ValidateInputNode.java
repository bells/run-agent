package cn.watsonzhu.runagent.workflow.node;

import java.util.Map;
import cn.watsonzhu.runagent.exception.InvalidRunningQueryException;
import cn.watsonzhu.runagent.exception.WorkflowException;
import cn.watsonzhu.runagent.service.RunningDateRange;
import cn.watsonzhu.runagent.workflow.TrainingAnalysisState;
import cn.watsonzhu.runagent.workflow.WorkflowMode;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

@Component
public class ValidateInputNode implements NodeAction<TrainingAnalysisState> {
    @Override public Map<String, Object> apply(TrainingAnalysisState state) {
        if (state.question() == null || state.question().isBlank() || state.question().length() > 4000 || state.mode() == null) {
            throw new WorkflowException(WorkflowException.Kind.INVALID_REQUEST);
        }
        if (state.mode() != WorkflowMode.KNOWLEDGE_ONLY || state.startDate() != null || state.endDate() != null) {
            try {
                new RunningDateRange(state.startDate(), state.endDate());
            } catch (InvalidRunningQueryException exception) {
                throw new WorkflowException(WorkflowException.Kind.INVALID_REQUEST);
            }
        }
        return Map.of();
    }
}
