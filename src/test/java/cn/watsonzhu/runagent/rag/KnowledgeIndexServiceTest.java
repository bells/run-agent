package cn.watsonzhu.runagent.rag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import cn.watsonzhu.runagent.config.RagProperties;
import cn.watsonzhu.runagent.exception.KnowledgeBaseUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeIndexServiceTest {
    @TempDir Path directory;

    @Test void chunksChineseTextPreservesMetadataAndRejectsLimitOverflow() {
        var embedding = new RagTestSupport.FakeEmbedding();
        var properties = new RagProperties(true, "", "unused.json", false, 80, 30, 10, 5000, 5, 0.5, Duration.ofSeconds(10));
        var service = new KnowledgeIndexService(properties, embedding, new KnowledgeDocumentLoader(), "fake", "local");
        var doc = new Document("自创训练知识：每周训练应留出恢复时间。轻松跑采用舒适强度，不追求每次都跑得更快！".repeat(80),
                Map.of("sourceTitle", "自创知识"));
        var chunks = service.chunk(List.of(doc));
        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(chunk -> {
            assertThat(chunk.getText()).isNotBlank();
            assertThat(chunk.getMetadata()).containsEntry("sourceTitle", "自创知识");
        });
        var limited = new RagProperties(true, "", "unused.json", false, 80, 30, 10, 1, 5, 0.5, Duration.ofSeconds(10));
        assertThatThrownBy(() -> new KnowledgeIndexService(limited, embedding, new KnowledgeDocumentLoader(), "fake", "local")
                .chunk(List.of(doc))).isInstanceOf(KnowledgeBaseUnavailableException.class);
    }

    @Test void indexesSavesReloadsWithoutEmbeddingAndRejectsChangedModel() throws Exception {
        Path source = directory.resolve("synthetic.txt"), path = directory.resolve("store.json");
        Files.writeString(source, "自创知识：轻松训练用于积累稳定的训练时间，运动者应按恢复状态调整训练量。");
        var model = new RagTestSupport.FakeEmbedding();
        var built = new KnowledgeIndexService(RagTestSupport.properties(source, path, true), model,
                new KnowledgeDocumentLoader(), "fake-v1", "local");
        built.initialize();
        assertThat(Files.size(path)).isPositive();
        assertThat(built.store().similaritySearch(SearchRequest.builder().query("轻松训练").topK(1).similarityThreshold(0.8).build()))
                .hasSize(1);
        var unusedModel = new RagTestSupport.FakeEmbedding();
        var forbiddenLoader = mock(KnowledgeDocumentLoader.class);
        var loaded = new KnowledgeIndexService(RagTestSupport.properties(source, path, false), unusedModel,
                forbiddenLoader, "fake-v1", "local");
        loaded.initialize();
        assertThat(loaded.store()).isNotNull();
        assertThat(unusedModel.calls).hasValue(0);
        verifyNoInteractions(forbiddenLoader);
        var incompatible = new KnowledgeIndexService(RagTestSupport.properties(source, path, false), unusedModel,
                forbiddenLoader, "fake-v2", "local");
        incompatible.initialize();
        assertThatThrownBy(incompatible::store).isInstanceOf(KnowledgeBaseUnavailableException.class);
    }

    @Test void failedEmbeddingDoesNotPublishPartialIndexOrReplaceSavedIndex() throws Exception {
        Path source = directory.resolve("synthetic.txt"), path = directory.resolve("store.json");
        Files.writeString(source, "自创知识：轻松训练用于稳定积累运动时长，训练之间要安排恢复。");
        Files.writeString(path, "previous index sentinel");
        var embedding = mock(org.springframework.ai.embedding.EmbeddingModel.class);
        when(embedding.dimensions()).thenReturn(3);
        when(embedding.embed(any(Document.class))).thenThrow(new IllegalStateException("private provider details"));
        var index = new KnowledgeIndexService(RagTestSupport.properties(source, path, true), embedding,
                new KnowledgeDocumentLoader(), "fake", "local");
        index.initialize();
        assertThatThrownBy(index::store).isInstanceOf(KnowledgeBaseUnavailableException.class);
        assertThat(Files.readString(path)).isEqualTo("previous index sentinel");
    }

    @Test void emptyOrCorruptStoreCannotBeLoadedAsReady() throws Exception {
        Path source = directory.resolve("source.txt"), path = directory.resolve("store.json");
        Files.writeString(source, "自创知识：轻松训练用于稳定积累运动时长，训练之间要安排恢复。");
        var model = new RagTestSupport.FakeEmbedding();
        var service = new KnowledgeIndexService(RagTestSupport.properties(source, path, true), model,
                new KnowledgeDocumentLoader(), "fake", "local");
        service.initialize();
        Files.writeString(path, "{}");
        var loaded = new KnowledgeIndexService(RagTestSupport.properties(source, path, false), model,
                new KnowledgeDocumentLoader(), "fake", "local");
        loaded.initialize();
        assertThatThrownBy(loaded::store).isInstanceOf(KnowledgeBaseUnavailableException.class);
    }
}
