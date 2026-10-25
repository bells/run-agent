package cn.watsonzhu.runagent.workflow.node;

import java.util.Map;
import java.util.UUID;
import cn.watsonzhu.runagent.exception.WorkflowException;
import cn.watsonzhu.runagent.rag.KnowledgeSearchService;
import cn.watsonzhu.runagent.workflow.KnowledgeEvidence;
import cn.watsonzhu.runagent.workflow.TrainingAnalysisState;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeRetrievalNode implements NodeAction<TrainingAnalysisState> {
    private final KnowledgeSearchService search;
    public KnowledgeRetrievalNode(KnowledgeSearchService search) { this.search = search; }

    @Override public Map<String, Object> apply(TrainingAnalysisState state) {
        try {
            var documents = search.retrieveDocuments(UUID.randomUUID().toString(), state.question());
            if (documents.isEmpty() || documents.stream().anyMatch(doc -> doc.getText() == null || doc.getText().isBlank())) {
                throw new WorkflowException(WorkflowException.Kind.DEPENDENCY_UNAVAILABLE);
            }
            var evidence = documents.stream().map(doc -> new KnowledgeEvidence(
                    doc.getMetadata().get("sourceTitle") instanceof String title ? title : "Unknown source",
                    doc.getScore(), doc.getText())).toList();
            return Map.of("knowledgeEvidence", evidence);
        } catch (RuntimeException exception) {
            throw new WorkflowException(WorkflowException.Kind.DEPENDENCY_UNAVAILABLE);
        }
    }
}
