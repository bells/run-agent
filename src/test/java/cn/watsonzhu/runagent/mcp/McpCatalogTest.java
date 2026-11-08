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
    @Test void specialistsUseConnectionSnapshotAndSendNoHostTraceToMcp() {
        var running = client("running", "running-mcp-server", "getRunningSummary", "getRecentRuns", "getPersonalBest");
        var weather = client("weather", "open-meteo-mcp-server", "openmeteo_search_locations", "openmeteo_get_forecast", "openmeteo_get_air_quality");
        var request = new AtomicReference<McpSchema.CallToolRequest>();
        when(running.callTool(any())).thenAnswer(call -> {
            request.set(call.getArgument(0));
            return McpSchema.CallToolResult.builder().isError(false).addTextContent("{\"runCount\":2}").build();
        });
        var catalog = new McpCatalog(List.of(running, weather), new McpPolicy("mcp").filter(), ToolContextToMcpMetaConverter.noOp());
        assertThat(catalog.getToolCallbacks("running")).extracting(t -> t.getToolDefinition().name())
                .containsExactlyInAnyOrder("getRunningSummary", "getRecentRuns", "getPersonalBest");
        assertThat(catalog.getToolCallbacks("weather")).extracting(t -> t.getToolDefinition().name())
                .containsExactlyInAnyOrder("openmeteo_search_locations", "openmeteo_get_forecast", "openmeteo_get_air_quality");
        assertThat(catalog.getToolCallbacks("unknown")).isEmpty();
        var control = new cn.watsonzhu.runagent.multiagent.MultiAgentExecutionControl(java.time.Duration.ofSeconds(5));
        var trace = new cn.watsonzhu.runagent.multiagent.MultiAgentExecutionTrace("synthetic-id",
                cn.watsonzhu.runagent.multiagent.Specialist.RUNNING_ANALYST, control, null, null, null);
        new cn.watsonzhu.runagent.multiagent.SpecialistTracingToolCallback(catalog.findTool("running", "getRunningSummary").orElseThrow())
                .call("{\"startDate\":\"2026-01-01\",\"endDate\":\"2026-01-31\"}",
                        new ToolContext(Map.of(cn.watsonzhu.runagent.multiagent.MultiAgentExecutionTrace.CONTEXT_KEY, trace)));
        assertThat(request.get().meta()).isEmpty();
        assertThat(request.get().arguments()).containsOnlyKeys("startDate", "endDate");
        assertThat(trace.successfulToolCalls()).isEqualTo(1);
        catalog.getToolCallbacks("running");
        verify(running, times(1)).initialize();
        verify(running, times(1)).listTools();
    }

    @Test void weatherStructuredContentReachesSpecialistCoordinateGuardWithoutSecondCall() {
        var weather = client("weather", "open-meteo-mcp-server", "openmeteo_search_locations", "openmeteo_get_forecast", "openmeteo_get_air_quality");
        var requests = new java.util.ArrayList<McpSchema.CallToolRequest>();
        when(weather.callTool(any())).thenAnswer(call -> {
            McpSchema.CallToolRequest request = call.getArgument(0);
            requests.add(request);
            Map<String, Object> structured = request.name().equals("openmeteo_search_locations")
                    ? Map.of("results", List.of(Map.of("latitude", 1, "longitude", 2)), "count", 1)
                    : Map.of("current", Map.of("temperature_2m", 18));
            return McpSchema.CallToolResult.builder().addTextContent("Human readable table without machine coordinates")
                    .structuredContent(structured).build();
        });
        var catalog = new McpCatalog(List.of(weather), new McpPolicy("local").filter(), ToolContextToMcpMetaConverter.noOp());
        var control = new cn.watsonzhu.runagent.multiagent.MultiAgentExecutionControl(java.time.Duration.ofSeconds(5));
        var trace = new cn.watsonzhu.runagent.multiagent.MultiAgentExecutionTrace("synthetic-weather-id",
                cn.watsonzhu.runagent.multiagent.Specialist.WEATHER_ADVISOR, control, null, null, "Synthetic City");
        var context = new ToolContext(Map.of(cn.watsonzhu.runagent.multiagent.MultiAgentExecutionTrace.CONTEXT_KEY, trace, "secret", "private-host-marker"));
        var search = new cn.watsonzhu.runagent.multiagent.SpecialistTracingToolCallback(catalog.findTool("weather", "openmeteo_search_locations").orElseThrow());
        var forecast = new cn.watsonzhu.runagent.multiagent.SpecialistTracingToolCallback(catalog.findTool("weather", "openmeteo_get_forecast").orElseThrow());
        String locations = search.call("{\"name\":\"wrong city\"}", context);
        assertThat(new tools.jackson.databind.json.JsonMapper().readTree(locations).path("results").size()).isEqualTo(1);
        assertThat(forecast.call("{\"latitude\":1,\"longitude\":2}", context)).contains("temperature_2m");
        assertThat(trace.successfulToolCalls()).isEqualTo(2);
        assertThat(requests).hasSize(2).allSatisfy(request -> assertThat(request.meta()).isEmpty());
        assertThat(requests.getFirst().arguments()).containsEntry("name", "Synthetic City");
        verify(weather, times(1)).listTools();
    }

    @Test void weatherContentOnlyServersKeepTheirLegacyResult() {
        var weather = client("weather", "open-meteo-mcp-server", "openmeteo_get_forecast");
        when(weather.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .addTextContent("{\"temperature_2m\":18}").build());
        var catalog = new McpCatalog(List.of(weather), new McpPolicy("local").filter(), ToolContextToMcpMetaConverter.noOp());
        String result = catalog.findTool("weather", "openmeteo_get_forecast").orElseThrow().call("{}");
        assertThat(new tools.jackson.databind.json.JsonMapper().readTree(result).isArray()).isTrue();
        assertThat(result).contains("temperature_2m");
        verify(weather, times(1)).callTool(any());
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
