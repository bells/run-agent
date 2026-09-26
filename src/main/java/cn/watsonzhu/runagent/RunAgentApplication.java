package cn.watsonzhu.runagent;

import cn.watsonzhu.runagent.config.RunningDataProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 启用配置绑定，让业务类通过 RunningDataProperties 获取数据路径。 */
@SpringBootApplication
@EnableConfigurationProperties(RunningDataProperties.class)
public class RunAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RunAgentApplication.class, args);
    }
}
