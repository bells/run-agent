package cn.watsonzhu.runagent.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import cn.watsonzhu.runagent.agent.AgentExecutionTrace;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class McpCatalogTest {
    @Test void weatherStartupFailureDoesNotRemoveRunningAndRequestsDoNotRediscoverTools() {
        var running = client("running", "running-mcp-server", "getRunningSummary");
        var weather = mock(McpSyncClient.class);
        when(weather.getClientInfo()).thenReturn(McpPolicyTest.connection("weather", "open-meteo-mcp-server").clientInfo());
        when(weather.initialize()).thenThrow(new IllegalStateException("private URL and payload"));
        var catalog = new McpCatalog(List.of(weather, running), new McpPolicy("mcp").filter(), ToolContextToMcpMetaConverter.noOp());
        assertThat(catalog.getToolCallbacks()).hasSize(1);
        catalog.getToolCallbacks();
        verify(running, times(1)).listTools();
    }
    @Test void runningStartupFailureDoesNotRemoveWeatherTools() {
        var weather = client("weather", "open-meteo-mcp-server", "openmeteo_search_locations",
                "openmeteo_get_forecast", "openmeteo_get_air_quality");
        var running = mock(McpSyncClient.class);
        when(running.getClientInfo()).thenReturn(McpPolicyTest.connection("running", "running-mcp-server").clientInfo());
        when(running.initialize()).thenThrow(new IllegalStateException("unavailable"));
        var catalog = new McpCatalog(List.of(running, weather), new McpPolicy("mcp").filter(), ToolContextToMcpMetaConverter.noOp());
        assertThat(catalog.getToolCallbacks()).extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("openmeteo_search_locations", "openmeteo_get_forecast", "openmeteo_get_air_quality");
    }
    @Test void timeoutBecomesSafeRunningDataErrorWithoutLosingTraceCount() {
        var client = client("running", "running-mcp-server", "getRunningSummary");
        when(client.callTool(any())).thenThrow(new RuntimeException(new java.util.concurrent.TimeoutException("private upstream")));
        var catalog = new McpCatalog(List.of(client), new McpPolicy("mcp").filter(), ToolContextToMcpMetaConverter.noOp());
        var trace = new AgentExecutionTrace("timeout", "conversation");
        assertThat(catalog.getToolCallbacks()[0].call("{}", new ToolContext(Map.of(AgentExecutionTrace.CONTEXT_KEY, trace))))
                .contains("RUNNING_DATA_UNAVAILABLE").doesNotContain("private", "upstream");
        assertThat(trace.toolCallCount()).isEqualTo(1);
    }
    @Test void actualMcpCallRequestHasNoHostContextMetadataAndIsErrorBecomesSafeResult() {
        var client = client("weather", "open-meteo-mcp-server", "openmeteo_get_forecast");
        var request = new AtomicReference<McpSchema.CallToolRequest>();
        when(client.callTool(any())).thenAnswer(invocation -> {
            request.set(invocation.getArgument(0));
            return McpSchema.CallToolResult.builder().isError(true).addTextContent("private forecast payload").build();
        });
        var catalog = new McpCatalog(List.of(client), new McpPolicy("mcp").filter(), ToolContextToMcpMetaConverter.noOp());
        var trace = new AgentExecutionTrace("test", "secret-conversation");
        String result = catalog.getToolCallbacks()[0].call("{\"latitude\":22.5}",
                new ToolContext(Map.of(AgentExecutionTrace.CONTEXT_KEY, trace, "secret", "do-not-send")));
        assertThat(request.get().meta()).isEmpty();
        assertThat(request.get().name()).isEqualTo("openmeteo_get_forecast");
        assertThat(request.get().arguments()).containsOnlyKeys("latitude");
        assertThat(result).contains("LIVE_WEATHER_UNAVAILABLE").doesNotContain("private", "forecast payload");
        assertThat(trace.toolCallCount()).isEqualTo(1);
    }
    static McpSyncClient client(String connection, String server, String... names) {
        var client = mock(McpSyncClient.class);
        var info = McpPolicyTest.connection(connection, server);
        when(client.getClientInfo()).thenReturn(info.clientInfo());
        when(client.getClientCapabilities()).thenReturn(info.clientCapabilities());
        when(client.initialize()).thenReturn(info.initializeResult());
        when(client.getCurrentInitializationResult()).thenReturn(info.initializeResult());
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                java.util.Arrays.stream(names).map(McpPolicyTest::tool).toList(), null));
        return client;
    }
}
