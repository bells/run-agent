package cn.watsonzhu.runagent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("run-agent.multi-agent")
public record MultiAgentProperties(Duration timeout, int maxDelegations, int maxReportChars, int maxTaskChars) {
    public MultiAgentProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("Multi-agent timeout must be positive and at most one hour");
        if (maxDelegations < 1 || maxDelegations > 3)
            throw new IllegalArgumentException("Multi-agent delegations must be between 1 and 3");
        if (maxReportChars < 500 || maxReportChars > 5000)
            throw new IllegalArgumentException("Multi-agent reports must be between 500 and 5000 characters");
        if (maxTaskChars < 1 || maxTaskChars > 4000)
            throw new IllegalArgumentException("Multi-agent tasks must be between 1 and 4000 characters");
    }
}
