package cn.watsonzhu.runagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "run-agent.memory")
public record MemoryProperties(int maxMessages) {
    public MemoryProperties {
        if (maxMessages < 1 || maxMessages > 200) {
            throw new IllegalArgumentException("Memory maxMessages must be between 1 and 200");
        }
    }
}
