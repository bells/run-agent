package cn.watsonzhu.runagent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("run-agent.workflow")
public record WorkflowProperties(Duration timeout) {
    public WorkflowProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Workflow timeout must be positive");
        }
    }
}
