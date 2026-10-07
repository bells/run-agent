package cn.watsonzhu.runagent.rag;

import java.util.List;
import java.util.UUID;
import cn.watsonzhu.runagent.config.RagProperties;
import cn.watsonzhu.runagent.exception.KnowledgeBaseUnavailableException;
import cn.watsonzhu.runagent.model.KnowledgeSearchResponse;
import cn.watsonzhu.runagent.model.KnowledgeSearchMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeSearchService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchService.class);
    private final RagProperties properties;
    private final ObjectProvider<KnowledgeIndexService> index;

    public KnowledgeSearchService(RagProperties properties, ObjectProvider<KnowledgeIndexService> index) {
        this.properties = properties;
        this.index = index;
    }

    public SearchRequest request() {
        return SearchRequest.builder().topK(properties.topK()).similarityThreshold(properties.similarityThreshold()).build();
    }

    public KnowledgeSearchResponse search(String query) {
        String queryId = UUID.randomUUID().toString();
        List<Document> documents = retrieve(queryId, SearchRequest.from(request()).query(query).build());
        var results = new java.util.ArrayList<KnowledgeSearchMatch>();
        for (Document document : documents) {
            results.add(new KnowledgeSearchMatch(results.size() + 1, document.getScore(), document.getId(),
                    metadata(document, "sourceTitle"), metadata(document, "pageNumber"), metadata(document, "chapter"),
                    preview(document.getText())));
        }
        return new KnowledgeSearchResponse(queryId, List.copyOf(results));
    }

    private List<Document> retrieve(String queryId, SearchRequest request) {
        if (!properties.enabled()) throw new KnowledgeBaseUnavailableException("Knowledge base is not enabled.");
        var service = index.getIfAvailable();
        if (service == null) throw new KnowledgeBaseUnavailableException("Knowledge index is not available.");
        long started = System.nanoTime();
        try {
            List<Document> matches = service.store().similaritySearch(request);
            log.info("rag queryId={} phase=RETRIEVED topK={} matches={} latencyMs={}",
                    queryId, request.getTopK(), matches.size(), KnowledgeIndexService.elapsed(started));
            for (int i = 0; i < matches.size(); i++) {
                var doc = matches.get(i);
                log.info("rag queryId={} rank={} score={} sourceTitle={} page={} chapter={} chunkId={}", queryId,
                        i + 1, doc.getScore(), metadata(doc, "sourceTitle"), metadata(doc, "pageNumber"),
                        metadata(doc, "chapter"), doc.getId());
            }
            return matches;
        } catch (RuntimeException exception) {
            log.warn("rag queryId={} phase=RETRIEVAL_FAILED latencyMs={}", queryId, KnowledgeIndexService.elapsed(started));
            throw new KnowledgeBaseUnavailableException("Knowledge retrieval is temporarily unavailable.");
        }
    }

    public VectorStore advisorStore(String queryId) {
        // A per-request read-only adapter gives the advisor the same observable retrieval path as /search.
        return new VectorStore() {
            public List<Document> similaritySearch(SearchRequest request) {
                var matches = retrieve(queryId, request);
                if (matches.isEmpty()) throw new NoKnowledgeContextException();
                return matches.stream().map(doc -> doc.mutate()
                        .text("来源：" + metadata(doc, "sourceTitle") + "\n" + doc.getText()).build()).toList();
            }
            public void add(List<Document> documents) { throw new UnsupportedOperationException(); }
            public void delete(List<String> ids) { throw new UnsupportedOperationException(); }
            public void delete(Filter.Expression expression) { throw new UnsupportedOperationException(); }
        };
    }

    static String metadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? null : value.toString();
    }

    static String preview(String text) {
        if (text == null) return "";
        int end = text.offsetByCodePoints(0, Math.min(200, text.codePointCount(0, text.length())));
        return text.substring(0, end);
    }

    static class NoKnowledgeContextException extends RuntimeException { }
}
