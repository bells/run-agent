package cn.watsonzhu.runagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingProperties;
import cn.watsonzhu.runagent.config.AgentProperties;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class RunAgentApplicationTests {

    @Autowired private ToolCallingProperties toolCallingProperties;
    @Autowired private AgentProperties agentProperties;

    @Test
    void contextLoads() {
        assertThat(toolCallingProperties.getLimits().getMaxCallsPerToolDefault()).isEqualTo(4);
        assertThat(toolCallingProperties.getLimits().getMaxTotalToolCalls()).isEqualTo(8);
        assertThat(toolCallingProperties.getLimits().getOnLimitExceeded().name()).isEqualTo("THROW");
        assertThat(agentProperties.timeout()).hasSeconds(30);
    }
}
