package cn.watsonzhu.runagent.workflow;

import java.time.LocalDate;

import cn.watsonzhu.runagent.exception.RunningDataUnavailableException;
import cn.watsonzhu.runagent.exception.WorkflowException;
import cn.watsonzhu.runagent.mcp.McpRunningStatistics;
import cn.watsonzhu.runagent.model.running.RunningSummary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "run-agent.running-tools.provider", havingValue = "mcp")
public class McpRunningCapabilityGateway implements RunningCapabilityGateway {
    private final McpRunningStatistics statistics;

    public McpRunningCapabilityGateway(McpRunningStatistics statistics) {
        this.statistics = statistics;
    }

    @Override
    public RunningSummary getRunningSummary(LocalDate start, LocalDate end) {
        try {
            return statistics.summary(start, end).value();
        } catch (RunningDataUnavailableException exception) {
            throw new WorkflowException(WorkflowException.Kind.DEPENDENCY_UNAVAILABLE);
        }
    }
}
