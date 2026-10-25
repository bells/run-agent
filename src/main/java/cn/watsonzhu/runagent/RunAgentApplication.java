package cn.watsonzhu.runagent;

import cn.watsonzhu.runagent.config.RunningDataProperties;
import cn.watsonzhu.runagent.config.AgentProperties;
import cn.watsonzhu.runagent.config.MemoryProperties;
import cn.watsonzhu.runagent.config.WorkflowProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 启用业务配置绑定。 */
@SpringBootApplication
@EnableConfigurationProperties({RunningDataProperties.class, AgentProperties.class, MemoryProperties.class, WorkflowProperties.class})
public class RunAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RunAgentApplication.class, args);
    }
}
