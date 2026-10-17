package cn.watsonzhu.runagent.mcp;

import java.util.Map;
import cn.watsonzhu.runagent.agent.AgentExecutionTrace;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import static org.assertj.core.api.Assertions.assertThat;

class TracingToolCallbackTest {
    @Test void countsLocallyPreservesContractAndStripsContextBeforeDelegation() {
        var trace = new AgentExecutionTrace("test", "conversation");
        var definition = ToolDefinition.builder().name("running_summary").description("summary").inputSchema("{}").build();
        var metadata = ToolMetadata.builder().returnDirect(true).build();
        ToolCallback delegate = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return definition; }
            public ToolMetadata getToolMetadata() { return metadata; }
            public String call(String input) { throw new AssertionError("context overload expected"); }
            public String call(String input, ToolContext context) {
                assertThat(context.getContext()).isEmpty();
                assertThat(input).isEqualTo("{}");
                return "result";
            }
        };
        var callback = new TracingToolCallback(delegate, "running");
        assertThat(callback.call("{}", new ToolContext(Map.of(AgentExecutionTrace.CONTEXT_KEY, trace, "secret", "private"))))
                .isEqualTo("result");
        assertThat(trace.toolCallCount()).isEqualTo(1);
        assertThat(callback.getToolDefinition()).isSameAs(definition);
        assertThat(callback.getToolMetadata()).isSameAs(metadata);
    }
    @Test void failureIsCountedAndOnlySafeSourceSpecificErrorReachesModel() {
        ToolCallback broken = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("weather").inputSchema("{}").build(); }
            public String call(String input) { throw new IllegalStateException("private location / credential / payload"); }
        };
        var trace = new AgentExecutionTrace("test", "conversation");
        String result = new TracingToolCallback(broken, "weather").call("{}",
                new ToolContext(Map.of(AgentExecutionTrace.CONTEXT_KEY, trace)));
        assertThat(result).contains("LIVE_WEATHER_UNAVAILABLE").doesNotContain("private", "credential", "payload");
        assertThat(trace.toolCallCount()).isEqualTo(1);
    }
}
