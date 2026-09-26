package cn.watsonzhu.runagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 数据文件路径由 RUNNING_DATA_PATH 提供；不把另一仓库的本机路径写死在代码中。 */
@ConfigurationProperties(prefix = "run-agent.running-data")
public record RunningDataProperties(String path) {
}
