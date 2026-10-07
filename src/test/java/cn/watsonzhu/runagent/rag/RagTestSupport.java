package cn.watsonzhu.runagent.rag;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import cn.watsonzhu.runagent.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

class RagTestSupport {
    static RagProperties properties(Path source, Path index, boolean reindex) {
        return new RagProperties(true, source.toString(), index.toString(), reindex,
                800, 350, 10, 5000, 5, 0.5, Duration.ofSeconds(10));
    }
    static class FakeEmbedding implements EmbeddingModel {
        final AtomicInteger calls = new AtomicInteger();
        public float[] embed(Document doc) { return embed(doc.getText()); }
        public EmbeddingResponse call(EmbeddingRequest request) {
            calls.incrementAndGet();
            var results = new java.util.ArrayList<Embedding>();
            for (String text : request.getInstructions()) {
                float[] vector = text.contains("轻松") ? new float[] {1, 0, 0}
                        : text.contains("阈值") ? new float[] {0, 1, 0} : new float[] {0, 0, 1};
                results.add(new Embedding(vector, results.size()));
            }
            return new EmbeddingResponse(List.copyOf(results));
        }
        public int dimensions() { return 3; }
    }
}
