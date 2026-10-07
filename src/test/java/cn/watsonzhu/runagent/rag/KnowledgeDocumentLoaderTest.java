package cn.watsonzhu.runagent.rag;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cn.watsonzhu.runagent.exception.KnowledgeBaseUnavailableException;
import static org.assertj.core.api.Assertions.*;

class KnowledgeDocumentLoaderTest {
    @TempDir Path directory;
    private final KnowledgeDocumentLoader loader = new KnowledgeDocumentLoader();

    @Test void loadsPortableSyntheticFixture() throws Exception {
        Path source = directory.resolve("synthetic-running-knowledge.txt");
        try (var input = new org.springframework.core.io.ClassPathResource("fixtures/synthetic-running-knowledge.txt").getInputStream()) {
            Files.copy(input, source);
        }
        assertThat(loader.load(source).getFirst().getText()).contains("自创训练知识");
    }

    @Test void loadsSyntheticTextWithOnlySafeMetadata() throws Exception {
        Path source = directory.resolve("自创训练说明.txt");
        Files.writeString(source, "自创知识：轻松训练采用能自然交谈的强度，训练安排需考虑个人恢复情况。");
        var docs = loader.load(source);
        assertThat(docs).hasSize(1);
        assertThat(docs.getFirst().getMetadata()).containsEntry("sourceTitle", "自创训练说明")
                .containsEntry("sourceType", "book");
        assertThat(docs.getFirst().getMetadata().toString()).doesNotContain(directory.toString());
    }

    @Test void choosesOneVersionPerBookAndIgnoresDisclaimers() throws Exception {
        for (String title : new String[] {"甲书", "乙书", "丙书", "丁书"}) {
            Path book = Files.createDirectory(directory.resolve(title));
            Files.writeString(book.resolve("a.md"), "自创知识：训练之后应安排足够的恢复。");
            Files.writeString(book.resolve("a.txt"), "自创知识：轻松跑采用舒适的强度并逐步增加时长。");
            Files.writeString(book.resolve("免责声明.txt"), "这是下载声明。");
            Files.writeString(book.resolve("a.mobi"), "unsupported");
        }
        assertThat(loader.load(directory)).hasSize(4).allSatisfy(doc -> {
            assertThat(doc.getText()).contains("轻松跑");
            assertThat(doc.getMetadata().get("filename")).isEqualTo("a.txt");
        });
    }

    @Test void epubHasPriorityOverText() throws Exception {
        Files.writeString(directory.resolve("book.epub"), "selection only");
        Files.writeString(directory.resolve("book.txt"), "selection only");
        assertThat(loader.selectSources(directory)).extracting(p -> p.getFileName().toString()).containsExactly("book.epub");
    }

    @Test void missingEmptyUnsupportedAndGarbledSourcesFailSafely() throws Exception {
        for (String name : new String[] {"empty.txt", "bad.epub", "bad.mobi", "garbled.txt"}) {
            Path source = directory.resolve(name);
            Files.writeString(source, name.equals("garbled.txt") ? "\uFFFD".repeat(100) : "");
            assertThatThrownBy(() -> loader.load(source)).isInstanceOf(KnowledgeBaseUnavailableException.class)
                    .hasMessage("Knowledge source could not be extracted safely.");
        }
        assertThatThrownBy(() -> loader.load(directory.resolve("missing.txt")))
                .isInstanceOf(KnowledgeBaseUnavailableException.class)
                .hasMessageNotContaining(directory.toString());
    }
}
