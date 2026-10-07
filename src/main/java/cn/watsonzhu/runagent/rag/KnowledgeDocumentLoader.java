package cn.watsonzhu.runagent.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import cn.watsonzhu.runagent.exception.KnowledgeBaseUnavailableException;

public class KnowledgeDocumentLoader {
    private static final List<String> FORMATS = List.of("epub", "txt", "md");

    public List<Document> load(Path source) {
        try {
            List<Path> selected = selectSources(source);
            List<Document> documents = new ArrayList<>();
            for (Path file : selected) {
                var resource = new FileSystemResource(file);
                List<Document> extracted = extension(file).equals("epub")
                        ? new TikaDocumentReader(resource).get() : new TextReader(resource).get();
                String title = Files.isDirectory(source) ? file.getParent().getFileName().toString()
                        : stem(file);
                // Reader metadata is untrusted; a fresh allowlist prevents local paths entering embeddings or prompts.
                String sourceId = UUID.nameUUIDFromBytes(title.getBytes(StandardCharsets.UTF_8)).toString();
                for (Document document : extracted) {
                    String text = document.getText();
                    if (text == null || text.isBlank()
                            || text.chars().filter(c -> c == '\uFFFD').count() > text.length() / 100) {
                        throw unavailable();
                    }
                    documents.add(new Document(text, Map.of("sourceId", sourceId, "sourceTitle", title,
                            "sourceType", "book", "filename", file.getFileName().toString())));
                }
                if (extracted.isEmpty()) throw unavailable();
            }
            if (documents.isEmpty()) throw unavailable();
            return List.copyOf(documents);
        } catch (IOException | RuntimeException exception) {
            throw unavailable();
        }
    }

    public List<Path> selectSources(Path source) throws IOException {
        if (Files.isRegularFile(source)) {
            if (!FORMATS.contains(extension(source))) throw unavailable();
            return List.of(source);
        }
        if (!Files.isDirectory(source)) throw unavailable();
        var direct = preferred(source);
        if (!direct.isEmpty()) return List.of(direct.getFirst());
        // A collection root contains one directory per book. Never index alternate editions of the same book twice.
        List<Path> result = new ArrayList<>();
        try (var entries = Files.list(source)) {
            for (Path directory : entries.filter(Files::isDirectory).sorted().toList()) {
                if (directory.getFileName().toString().startsWith(".")) continue;
                var choices = preferred(directory);
                if (choices.isEmpty()) throw unavailable();
                result.add(choices.getFirst());
            }
        }
        if (result.isEmpty()) throw unavailable();
        return List.copyOf(result);
    }

    private List<Path> preferred(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).filter(p -> FORMATS.contains(extension(p)))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !stem(p).equals("免责声明"))
                    .sorted(Comparator.<Path>comparingInt(p -> FORMATS.indexOf(extension(p)))
                            .thenComparing(p -> p.getFileName().toString())).toList();
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static KnowledgeBaseUnavailableException unavailable() {
        return new KnowledgeBaseUnavailableException("Knowledge source could not be extracted safely.");
    }
}
