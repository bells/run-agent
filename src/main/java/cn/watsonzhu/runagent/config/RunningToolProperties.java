package cn.watsonzhu.runagent.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("run-agent.running-tools")
public record RunningToolProperties(String provider) {
    public RunningToolProperties {
        provider = provider == null ? "local" : provider;
        if (!Set.of("local", "mcp").contains(provider)) {
            throw new IllegalArgumentException("RUNNING_TOOL_PROVIDER must be local or mcp");
        }
    }
}
