package cn.watsonzhu.runagent.rag;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import cn.watsonzhu.runagent.exception.KnowledgeBaseUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeQaServiceTest {
    @TempDir Path directory;

    @Test void advisorRetrievesExactlyOnceAndInjectsContextWithoutToolsOrMemory() throws Exception {
        var captured = new AtomicReference<Prompt>();
        ChatModel chat = prompt -> {
            captured.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("stub paraphrase"))));
        };
        var store = store();
        var search = search(store);
        var qa = new KnowledgeQaService(ChatClient.builder(chat), search, new ClassPathResource("prompts/running-knowledge-system.txt"));
        assertThat(qa.ask("轻松训练有什么作用？").content()).isEqualTo("stub paraphrase");
        assertThat(captured.get().getUserMessage().getText()).contains("自创知识：轻松训练", "来源：合成训练手册");
        assertThat(captured.get().getInstructions()).hasSize(2);
        verify(store, times(1)).similaritySearch(any(SearchRequest.class));
    }

    @Test void noContextReturnsDeterministicMessageWithoutCallingChat() throws Exception {
        ChatModel chat = mock(ChatModel.class);
        when(chat.getOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
        var store = store();
        var qa = new KnowledgeQaService(ChatClient.builder(chat), search(store), new ClassPathResource("prompts/running-knowledge-system.txt"));
        assertThat(qa.ask("ShardingSphere分表配置").content()).isEqualTo(KnowledgeQaService.NO_CONTEXT);
        verify(chat, never()).call(any(Prompt.class));
    }

    @Test void disabledKnowledgeNeverRetrievesOrCallsChat() throws Exception {
        var properties = new cn.watsonzhu.runagent.config.RagProperties(false, "", "unused.json", false,
                800, 350, 10, 5000, 5, 0.5, java.time.Duration.ofSeconds(10));
        @SuppressWarnings("unchecked") ObjectProvider<KnowledgeIndexService> provider = mock(ObjectProvider.class);
        ChatModel chat = mock(ChatModel.class);
        when(chat.getOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
        var qa = new KnowledgeQaService(ChatClient.builder(chat), new KnowledgeSearchService(properties, provider),
                new ClassPathResource("prompts/running-knowledge-system.txt"));
        assertThatThrownBy(() -> qa.ask("轻松训练"))
                .isInstanceOf(KnowledgeBaseUnavailableException.class).hasMessage("Knowledge base is not enabled.");
        verifyNoInteractions(provider);
        verify(chat, never()).call(any(Prompt.class));
    }

    @Test void searchRespectsTopKAndThresholdAndCapsPreview() {
        var store = store();
        assertThat(store.similaritySearch(SearchRequest.builder().query("轻松").topK(1).similarityThreshold(0.5).build()))
                .hasSize(1).allSatisfy(doc -> assertThat(doc.getScore()).isEqualTo(1));
        var search = search(store);
        var response = search.search("轻松");
        assertThat(response.matches()).hasSize(2).allSatisfy(match -> {
            assertThat(match.preview().codePointCount(0, match.preview().length())).isLessThanOrEqualTo(200);
            assertThat(match.sourceTitle()).isEqualTo("合成训练手册");
        });
        assertThat(search.search("unknown topic").matches()).isEmpty();
    }

    private SimpleVectorStore store() {
        var store = spy(SimpleVectorStore.builder(new RagTestSupport.FakeEmbedding()).build());
        store.add(List.of(new Document("自创知识：轻松训练应采用可交谈强度。".repeat(30), Map.of("sourceTitle", "合成训练手册")),
                new Document("自创知识：轻松训练后安排恢复。", Map.of("sourceTitle", "合成训练手册")),
                new Document("自创知识：阈值训练采用受控强度。", Map.of("sourceTitle", "合成训练手册"))));
        return store;
    }

    private KnowledgeSearchService search(SimpleVectorStore store) {
        @SuppressWarnings("unchecked") ObjectProvider<KnowledgeIndexService> provider = mock(ObjectProvider.class);
        var index = mock(KnowledgeIndexService.class);
        when(provider.getIfAvailable()).thenReturn(index);
        when(index.store()).thenReturn(store);
        return new KnowledgeSearchService(RagTestSupport.properties(directory, directory.resolve("store.json"), false), provider);
    }
}
