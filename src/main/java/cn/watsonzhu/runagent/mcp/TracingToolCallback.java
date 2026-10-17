package cn.watsonzhu.runagent.mcp;

import java.time.Duration;
import java.util.Map;
import cn.watsonzhu.runagent.agent.AgentExecutionTrace;
import cn.watsonzhu.runagent.exception.AiRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Trace stays in the Host; only schema-defined arguments reach the MCP adapter. */
public final class TracingToolCallback implements ToolCallback {
    private static final Logger log = LoggerFactory.getLogger(TracingToolCallback.class);
    private final ToolCallback delegate;
    private final String connection;

    public TracingToolCallback(ToolCallback delegate, String connection) {
        this.delegate = delegate;
        this.connection = connection;
    }
    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
    @Override public String call(String input) { return call(input, null); }

    @Override public String call(String input, ToolContext context) {
        if (Thread.currentThread().isInterrupted()) {
            throw new AiRequestException("Agent execution cancelled", null);
        }
        Object value = context == null ? null : context.getContext().get(AgentExecutionTrace.CONTEXT_KEY);
        AgentExecutionTrace trace = value instanceof AgentExecutionTrace execution ? execution : null;
        int step = trace == null ? 0 : trace.nextToolCall();
        long started = System.nanoTime();
        String status = "FAILED";
        try {
            String result = delegate.call(input, new ToolContext(Map.of()));
            status = "SUCCESS";
            return result;
        } catch (RuntimeException exception) {
            if (Thread.currentThread().isInterrupted()) {
                throw new AiRequestException("Agent execution cancelled", null);
            }
            return "weather".equals(connection)
                    ? "{\"error\":\"LIVE_WEATHER_UNAVAILABLE\",\"message\":\"实时天气或空气质量暂时不可用，不得猜测。\"}"
                    : "{\"error\":\"RUNNING_DATA_UNAVAILABLE\",\"message\":\"个人跑步数据暂时不可用，不得猜测。\"}";
        } finally {
            log.info("agentExecutionId={} step={} tool={} source=mcp server={} status={} latencyMs={}",
                    trace == null ? "none" : trace.executionId(), step, getToolDefinition().name(), connection,
                    status, Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
    }
}
