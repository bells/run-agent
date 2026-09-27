package cn.watsonzhu.runagent.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "run-agent.agent")
public record AgentProperties(Duration timeout) {
    public AgentProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Agent timeout must be positive");
        }
    }
}
