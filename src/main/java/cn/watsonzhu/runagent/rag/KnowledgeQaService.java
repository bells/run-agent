package cn.watsonzhu.runagent.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import cn.watsonzhu.runagent.exception.AiRequestException;
import cn.watsonzhu.runagent.exception.KnowledgeBaseUnavailableException;
import cn.watsonzhu.runagent.model.KnowledgeAnswerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeQaService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeQaService.class);
    public static final String NO_CONTEXT = "当前跑步知识库没有找到足够相关的内容，无法依据书籍回答这个问题。";
    private final ChatClient client;
    private final KnowledgeSearchService search;
    private final String prompt;

    public KnowledgeQaService(ChatClient.Builder builder, KnowledgeSearchService search,
            @Value("classpath:prompts/running-knowledge-system.txt") Resource prompt) throws IOException {
        this.client = builder.build();
        this.search = search;
        this.prompt = prompt.getContentAsString(StandardCharsets.UTF_8);
    }

    public KnowledgeAnswerResponse ask(String question) {
        String queryId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        log.info("rag queryId={} phase=STARTED", queryId);
        try {
            var advisor = QuestionAnswerAdvisor.builder(search.advisorStore(queryId)).searchRequest(search.request()).build();
            String content = client.prompt().system(prompt).user(question).advisors(advisor).call().content();
            if (content == null || content.isBlank()) throw new AiRequestException("Knowledge answer was empty", null);
            log.info("rag queryId={} phase=COMPLETED latencyMs={}", queryId, KnowledgeIndexService.elapsed(started));
            return new KnowledgeAnswerResponse(queryId, content);
        } catch (KnowledgeSearchService.NoKnowledgeContextException exception) {
            log.info("rag queryId={} phase=NO_CONTEXT latencyMs={}", queryId, KnowledgeIndexService.elapsed(started));
            return new KnowledgeAnswerResponse(queryId, NO_CONTEXT);
        } catch (KnowledgeBaseUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("rag queryId={} phase=GENERATION_FAILED latencyMs={}", queryId, KnowledgeIndexService.elapsed(started));
            throw new AiRequestException("Knowledge model invocation failed", exception);
        }
    }
}
