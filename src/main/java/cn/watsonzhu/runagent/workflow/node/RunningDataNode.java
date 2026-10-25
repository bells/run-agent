package cn.watsonzhu.runagent.workflow.node;

import java.util.Map;
import cn.watsonzhu.runagent.exception.WorkflowException;
import cn.watsonzhu.runagent.workflow.RunningCapabilityGateway;
import cn.watsonzhu.runagent.workflow.TrainingAnalysisState;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

@Component
public class RunningDataNode implements NodeAction<TrainingAnalysisState> {
    private final RunningCapabilityGateway running;
    public RunningDataNode(RunningCapabilityGateway running) { this.running = running; }

    @Override public Map<String, Object> apply(TrainingAnalysisState state) {
        try {
            var summary = running.getRunningSummary(state.startDate(), state.endDate());
            if (summary == null) throw new WorkflowException(WorkflowException.Kind.DEPENDENCY_UNAVAILABLE);
            return Map.of("runningSummary", summary);
        } catch (RuntimeException exception) {
            throw new WorkflowException(WorkflowException.Kind.DEPENDENCY_UNAVAILABLE);
        }
    }
}
