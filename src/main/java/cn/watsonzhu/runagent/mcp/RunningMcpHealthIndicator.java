package cn.watsonzhu.runagent.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("runningMcp")
@ConditionalOnProperty(name = "run-agent.private-training.enabled", havingValue = "true")
public class RunningMcpHealthIndicator implements HealthIndicator {
    private final McpRunningStatistics statistics;

    private final boolean privateChatEnabled;

    public RunningMcpHealthIndicator(McpRunningStatistics statistics,
            @Value("${run-agent.private-chat.enabled:false}") boolean privateChatEnabled) {
        this.statistics = statistics;
        this.privateChatEnabled = privateChatEnabled;
    }

    @Override
    public Health health() {
        return privateChatEnabled && statistics.hasRunningTools() ? Health.up().build() : Health.down().build();
    }
}
