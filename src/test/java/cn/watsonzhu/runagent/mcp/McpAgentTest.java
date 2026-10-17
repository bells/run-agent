package cn.watsonzhu.runagent.mcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import cn.watsonzhu.runagent.agent.RunAgentService;
import cn.watsonzhu.runagent.prompt.PromptCatalog;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;
import io.micrometer.observation.ObservationRegistry;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpAgentTest {
    @Test void threeRemoteStepsAndMemoryUseTheExistingAdvisorWithoutLocalRunningData() {
        var client = McpCatalogTest.client("running", "running-mcp-server", "getRunningSummary", "getRecentRuns", "getPersonalBest");
        var executed = new ArrayList<String>();
        when(client.callTool(any())).thenAnswer(call -> {
            McpSchema.CallToolRequest request = call.getArgument(0);
            assertThat(request.meta()).isEmpty();
            executed.add(request.name());
            return McpSchema.CallToolResult.builder().addTextContent("{\"runCount\":2,\"totalDistanceKm\":15}").build();
        });
        var catalog = new McpCatalog(List.of(client), new McpPolicy("mcp").filter(), ToolContextToMcpMetaConverter.noOp());
        String summary = catalog.getToolCallbacks()[0].getToolDefinition().name();
        String recent = catalog.getToolCallbacks()[1].getToolDefinition().name();
        var rounds = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
            public ChatResponse call(Prompt prompt) {
                int round = rounds.getAndIncrement();
                if (round == 4) {
                    assertThat(prompt.getInstructions()).anyMatch(m -> m instanceof UserMessage && m.getText().equals("first period"));
                    assertThat(prompt.getInstructions()).noneMatch(ToolResponseMessage.class::isInstance);
                }
                return switch (round) {
                    case 0 -> tool(summary, "{\"startDate\":\"2026-09-18\",\"endDate\":\"2026-10-17\"}");
                    case 1 -> tool(summary, "{\"startDate\":\"2026-08-19\",\"endDate\":\"2026-09-17\"}");
                    case 2 -> tool(recent, "{\"startDate\":\"2026-09-18\",\"endDate\":\"2026-10-17\",\"limit\":5}");
                    case 4 -> tool(summary, "{\"startDate\":\"2026-08-19\",\"endDate\":\"2026-09-17\"}");
                    default -> answer("Based on retrieved running data.");
                };
            }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.empty(); }
        };
        var service = service(model, catalog, ToolCallingManager.builder().maxCallsPerTool(4).maxTotalToolCalls(8).build());
        assertThat(service.agent("memory-mcp", "first period").toolCallCount()).isEqualTo(3);
        assertThat(service.agent("memory-mcp", "compare with previous period").toolCallCount()).isEqualTo(1);
        assertThat(executed).containsExactly("getRunningSummary", "getRunningSummary", "getRecentRuns", "getRunningSummary");
        verify(client, times(1)).listTools();
    }
    @Test void mcpCallbacksStillObeyTheFrameworkCallLimit() {
        var client = McpCatalogTest.client("running", "running-mcp-server", "getRunningSummary");
        when(client.callTool(any())).thenReturn(McpSchema.CallToolResult.builder().addTextContent("{}").build());
        var tools = new McpCatalog(List.of(client), new McpPolicy("mcp").filter(), ToolContextToMcpMetaConverter.noOp());
        ChatModel repeating = new ChatModel() {
            public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
            public ChatResponse call(Prompt prompt) { return tool(tools.getToolCallbacks()[0].getToolDefinition().name(), "{}"); }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.empty(); }
        };
        var service = service(repeating, tools, ToolCallingManager.builder().maxCallsPerTool(2).maxTotalToolCalls(3).build());
        assertThatThrownBy(() -> service.agent("limit-mcp", "repeat"))
                .isInstanceOf(cn.watsonzhu.runagent.exception.AgentLimitException.class);
        verify(client, times(2)).callTool(any());
    }
    private static RunAgentService service(ChatModel model, ToolCallbackProvider tools, ToolCallingManager manager) {
        var builder = ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
                ToolCallingAdvisor.builder().toolCallingManager(manager));
        var resource = new ByteArrayResource("Test prompt".getBytes(StandardCharsets.UTF_8));
        return new RunAgentService(builder, new PromptCatalog(resource, resource), tools,
                MessageWindowChatMemory.builder().maxMessages(20).build());
    }
    static ChatResponse tool(String name, String args) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call", "function", name, args))).build())));
    }
    static ChatResponse answer(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
}
