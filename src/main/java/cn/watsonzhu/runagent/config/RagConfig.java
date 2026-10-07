package cn.watsonzhu.runagent.config;

import cn.watsonzhu.runagent.rag.KnowledgeDocumentLoader;
import cn.watsonzhu.runagent.rag.KnowledgeIndexService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.ollama.autoconfigure.OllamaConnectionProperties;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {
    @Bean
    @ConditionalOnProperty(prefix = "run-agent.rag", name = "enabled", havingValue = "true")
    KnowledgeIndexService knowledgeIndexService(RagProperties properties, EmbeddingModel embeddingModel,
            OllamaEmbeddingProperties embedding, OllamaConnectionProperties connection) {
        return new KnowledgeIndexService(properties, embeddingModel, new KnowledgeDocumentLoader(),
                embedding.getModel(), connection.getBaseUrl());
    }

    @Bean
    @ConditionalOnProperty(prefix = "run-agent.rag", name = "enabled", havingValue = "true")
    ApplicationRunner initializeKnowledgeIndex(KnowledgeIndexService index) {
        return args -> index.initialize();
    }
}
