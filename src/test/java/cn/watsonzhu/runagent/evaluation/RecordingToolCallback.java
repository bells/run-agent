package cn.watsonzhu.runagent.evaluation;

import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.json.JsonMapper;
import static cn.watsonzhu.runagent.evaluation.EvaluationModels.*;

/** Evaluation-only recorder. Raw arguments remain in memory and never enter report DTOs. */
public final class RecordingToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final List<ToolCall> calls;
    public RecordingToolCallback(ToolCallback delegate, List<ToolCall> calls) { this.delegate = delegate; this.calls = calls; }
    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
    @Override public String call(String input) { return call(input, new ToolContext(java.util.Map.of())); }
    @Override public String call(String input, ToolContext context) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Evaluation cancelled");
        calls.add(new ToolCall(getToolDefinition().name(), new JsonMapper().readTree(input)));
        return delegate.call(input, context);
    }
}
