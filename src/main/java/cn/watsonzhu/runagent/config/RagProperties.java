package cn.watsonzhu.runagent.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

@ConfigurationProperties(prefix = "run-agent.rag")
public record RagProperties(boolean enabled, String sourcePath, String vectorStorePath, boolean reindex,
        int chunkSize, int minChunkSizeChars, int minChunkLengthToEmbed, int maxNumChunks,
        int topK, double similarityThreshold, Duration timeout) {
    public RagProperties {
        if (chunkSize < 1 || minChunkSizeChars < 0 || minChunkLengthToEmbed < 0 || maxNumChunks < 1
                || topK < 1 || topK > 100 || !Double.isFinite(similarityThreshold)
                || similarityThreshold < 0 || similarityThreshold > 1
                || timeout == null || timeout.isNegative() || timeout.isZero()
                || vectorStorePath == null || vectorStorePath.isBlank()) {
            throw new IllegalArgumentException("Invalid RAG configuration");
        }
    }

    public TokenTextSplitter splitter() {
        return TokenTextSplitter.builder().withChunkSize(chunkSize).withMinChunkSizeChars(minChunkSizeChars)
                .withMinChunkLengthToEmbed(minChunkLengthToEmbed).withMaxNumChunks(maxNumChunks)
                .withKeepSeparator(true).withPunctuationMarks(List.of('。', '？', '！', '；', '\n', '.', '?', '!', ';'))
                .build();
    }
}
