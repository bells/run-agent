package cn.watsonzhu.runagent.rag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import cn.watsonzhu.runagent.config.RagProperties;
import cn.watsonzhu.runagent.exception.KnowledgeBaseUnavailableException;

public class KnowledgeIndexService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);
    private final RagProperties properties;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeDocumentLoader loader;
    private final String modelName;
    private final String endpoint;
    private volatile VectorStore readyStore;

    public KnowledgeIndexService(RagProperties properties, EmbeddingModel embeddingModel,
            KnowledgeDocumentLoader loader, String modelName, String endpoint) {
        this.properties = properties;
        this.embeddingModel = embeddingModel;
        this.loader = loader;
        this.modelName = modelName;
        this.endpoint = endpoint;
    }

    public void initialize() {
        readyStore = null;
        long started = System.nanoTime();
        try {
            Path path = Path.of(properties.vectorStorePath()).toAbsolutePath();
            Path manifest = Path.of(path + ".manifest");
            SimpleVectorStore candidate = SimpleVectorStore.builder(embeddingModel).build();
            if (Files.exists(path) && !properties.reindex()) {
                Properties saved = new Properties();
                try (var input = Files.newInputStream(manifest)) { saved.load(input); }
                if (!fingerprint().equals(saved.getProperty("fingerprint"))) {
                    throw new IllegalStateException("Index configuration changed");
                }
                if (!checksum(path).equals(saved.getProperty("checksum"))) {
                    throw new IllegalStateException("Index persistence is incomplete or corrupted");
                }
                candidate.load(path.toFile());
                validateSavedStore(path, Integer.parseInt(saved.getProperty("chunks")));
                log.info("rag phase=LOADED chunks={} embeddingModel={} latencyMs={}",
                        saved.getProperty("chunks"), modelName, elapsed(started));
            } else {
                if (properties.sourcePath() == null || properties.sourcePath().isBlank()) {
                    throw new IllegalStateException("Source is required");
                }
                var documents = loader.load(Path.of(properties.sourcePath()));
                for (var document : documents) {
                    log.info("rag phase=EXTRACTED sourceTitle={} documents=1 characters={}",
                            document.getMetadata().get("sourceTitle"), document.getText().length());
                }
                var chunks = chunk(documents);
                log.info("rag phase=CHUNKED documents={} chunks={}", documents.size(), chunks.size());
                candidate.add(chunks);
                persist(candidate, path, manifest, chunks.size());
                log.info("rag phase=INDEXED documents={} chunks={} embeddingModel={} latencyMs={} bytes={}",
                        documents.size(), chunks.size(), modelName, elapsed(started), Files.size(path));
            }
            // Publish only after the whole index has been embedded, persisted and validated.
            readyStore = candidate;
        } catch (Exception exception) {
            // Reader/provider exceptions may contain local paths or book text. Never log their message/stack.
            log.warn("rag phase=UNAVAILABLE reason=INDEX_INITIALIZATION_FAILED latencyMs={} action=check_local_source_model_or_reindex",
                    elapsed(started));
        }
    }

    public List<Document> chunk(List<Document> documents) {
        var chunks = properties.splitter().apply(documents);
        // TokenTextSplitter appends remaining tokens when its loop limit is reached; reject that oversized tail.
        if (chunks.isEmpty() || chunks.size() > properties.maxNumChunks()) {
            throw new KnowledgeBaseUnavailableException("Knowledge chunk limit exceeded or no usable chunks.");
        }
        return chunks;
    }

    public VectorStore store() {
        if (readyStore == null) throw new KnowledgeBaseUnavailableException("Knowledge index is not available.");
        return readyStore;
    }

    private void persist(SimpleVectorStore candidate, Path path, Path manifest, int count) throws Exception {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "index-", ".json");
        Path temporaryManifest = Files.createTempFile(path.getParent(), "index-", ".manifest");
        try {
            candidate.save(temporary.toFile());
            validateSavedStore(temporary, count);
            Properties info = new Properties();
            info.setProperty("fingerprint", fingerprint());
            info.setProperty("chunks", Integer.toString(count));
            info.setProperty("checksum", checksum(temporary));
            try (var output = Files.newOutputStream(temporaryManifest)) { info.store(output, "RunAgent RAG index"); }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporaryManifest, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(temporaryManifest);
        }
    }

    private void validateSavedStore(Path path, int count) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(path.toFile());
        if (!json.isObject() || count < 1 || count > properties.maxNumChunks() || json.size() != count) {
            throw new IllegalStateException("Invalid index");
        }
        int dimension = -1;
        for (var entry : json) {
            var vector = entry.path("embedding");
            if (!vector.isArray() || vector.isEmpty() || !entry.path("text").isTextual()
                    || entry.path("text").asText().isBlank()) throw new IllegalStateException("Invalid vector");
            if (dimension == -1) dimension = vector.size();
            if (dimension != vector.size()) throw new IllegalStateException("Inconsistent dimensions");
            double norm = 0;
            for (var value : vector) {
                if (!value.isNumber() || !Double.isFinite(value.asDouble())) throw new IllegalStateException("Invalid vector");
                norm += value.asDouble() * value.asDouble();
            }
            if (norm == 0) throw new IllegalStateException("Zero vector");
        }
        log.info("rag phase=VALIDATED chunks={} dimension={}", count, dimension);
    }

    private String fingerprint() throws Exception {
        String identity = modelName + "|" + endpoint + "|" + properties.chunkSize() + "|"
                + properties.minChunkSizeChars() + "|" + properties.minChunkLengthToEmbed() + "|v05";
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
    }

    private String checksum(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
