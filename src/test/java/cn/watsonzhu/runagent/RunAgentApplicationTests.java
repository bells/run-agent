package cn.watsonzhu.runagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingProperties;
import cn.watsonzhu.runagent.config.AgentProperties;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.ai.openai.api-key=test-key", "run-agent.rag.enabled=false"})
class RunAgentApplicationTests {

    @Autowired private ToolCallingProperties toolCallingProperties;
    @Autowired private AgentProperties agentProperties;

    @Autowired private org.springframework.ai.chat.model.ChatModel chatModel;
    @Autowired private org.springframework.ai.embedding.EmbeddingModel embeddingModel;
    @Autowired private org.springframework.context.ApplicationContext context;

    @Test
    void ragIsDisabledWithoutReplacingChatOrInitializingIndex() {
        assertThat(chatModel).isInstanceOf(org.springframework.ai.openai.OpenAiChatModel.class);
        assertThat(embeddingModel).isInstanceOf(org.springframework.ai.ollama.OllamaEmbeddingModel.class);
        assertThat(context.getBeansOfType(cn.watsonzhu.runagent.rag.KnowledgeIndexService.class)).isEmpty();
    }

    @Test
    void contextLoads() {
        assertThat(toolCallingProperties.getLimits().getMaxCallsPerToolDefault()).isEqualTo(4);
        assertThat(toolCallingProperties.getLimits().getMaxTotalToolCalls()).isEqualTo(8);
        assertThat(toolCallingProperties.getLimits().getOnLimitExceeded().name()).isEqualTo("THROW");
        assertThat(agentProperties.timeout()).hasSeconds(30);
    }
}
