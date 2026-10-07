package cn.watsonzhu.runagent.config;

import cn.watsonzhu.runagent.agent.RunAgentService;
import cn.watsonzhu.runagent.exception.KnowledgeBaseUnavailableException;
import cn.watsonzhu.runagent.rag.KnowledgeIndexService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key",
        "run-agent.rag.enabled=true",
        "run-agent.rag.source-path=missing-synthetic-book-for-test",
        "run-agent.rag.vector-store-path=build/nonexistent-rag-test/index.json",
        "run-agent.rag.reindex=true"
})
class RagUnavailableStartupTest {
    @Autowired KnowledgeIndexService index;
    @Autowired RunAgentService existingService;

    @Test void missingRagEnvironmentDoesNotBreakExistingApplication() {
        assertThat(existingService).isNotNull();
        assertThatThrownBy(index::store).isInstanceOf(KnowledgeBaseUnavailableException.class)
                .hasMessage("Knowledge index is not available.");
    }
}
