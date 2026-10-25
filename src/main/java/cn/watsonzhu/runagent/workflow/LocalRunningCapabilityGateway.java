package cn.watsonzhu.runagent.workflow;

import java.time.LocalDate;
import cn.watsonzhu.runagent.model.running.RunningSummary;
import cn.watsonzhu.runagent.service.RunningDataService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "run-agent.running-tools.provider", havingValue = "local", matchIfMissing = true)
public class LocalRunningCapabilityGateway implements RunningCapabilityGateway {
    private final RunningDataService data;

    public LocalRunningCapabilityGateway(RunningDataService data) { this.data = data; }

    @Override public RunningSummary getRunningSummary(LocalDate startDate, LocalDate endDate) {
        return data.summarize(startDate, endDate);
    }
}
