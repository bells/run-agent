package cn.watsonzhu.runagent.workflow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import cn.watsonzhu.runagent.model.TrainingAnalysisRequest;
import cn.watsonzhu.runagent.model.running.RunningSummary;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

public final class TrainingAnalysisState extends AgentState {
    public static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
            Map.entry("executionId", override()), Map.entry("question", override()),
            Map.entry("mode", override()), Map.entry("startDate", override()), Map.entry("endDate", override()),
            Map.entry("runningSummary", override()), Map.entry("knowledgeEvidence", override()),
            Map.entry("analysis", override()), Map.entry("deadlineNanos", override()),
            Map.entry("executedNodes", Channels.<String>appender(ArrayList::new)));

    public TrainingAnalysisState(Map<String, Object> data) { super(data); }

    public static Map<String, Object> initial(TrainingAnalysisRequest request, String executionId, long deadlineNanos) {
        Map<String, Object> data = new HashMap<>();
        data.put("executionId", executionId);
        data.put("question", request.question());
        data.put("mode", request.mode());
        data.put("startDate", request.startDate());
        data.put("endDate", request.endDate());
        data.put("deadlineNanos", deadlineNanos);
        return data;
    }

    public String executionId() { return this.<String>value("executionId").orElse(null); }
    public String question() { return this.<String>value("question").orElse(null); }
    public WorkflowMode mode() { return this.<WorkflowMode>value("mode").orElse(null); }
    public LocalDate startDate() { return this.<LocalDate>value("startDate").orElse(null); }
    public LocalDate endDate() { return this.<LocalDate>value("endDate").orElse(null); }
    public RunningSummary runningSummary() { return this.<RunningSummary>value("runningSummary").orElse(null); }
    public List<KnowledgeEvidence> knowledgeEvidence() { return this.<List<KnowledgeEvidence>>value("knowledgeEvidence").orElse(List.of()); }
    public String analysis() { return this.<String>value("analysis").orElse(null); }
    public List<String> executedNodes() { return List.copyOf(this.<List<String>>value("executedNodes").orElse(List.of())); }
    public long deadlineNanos() { return this.<Long>value("deadlineNanos").orElse(Long.MAX_VALUE); }

    @Override public String toString() { return "TrainingAnalysisState[executionId=" + executionId() + "]"; }

    private static Channel<Object> override() { return Channels.base((previous, update) -> update); }
}
