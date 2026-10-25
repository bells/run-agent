package cn.watsonzhu.runagent.workflow;

import java.time.LocalDate;
import cn.watsonzhu.runagent.model.running.RunningSummary;

public interface RunningCapabilityGateway {
    RunningSummary getRunningSummary(LocalDate startDate, LocalDate endDate);
}
