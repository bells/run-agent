package cn.watsonzhu.runagent.mcp;

import java.util.List;
import java.util.Map;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class McpPolicyTest {
    static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder().name(name).description("read only")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null)).build();
    }
    static McpConnectionInfo connection(String connection, String server) {
        return new McpConnectionInfo(McpSchema.ClientCapabilities.builder().build(),
                McpSchema.Implementation.builder("run-agent - " + connection, "0.6").title(connection).build(),
                McpSchema.InitializeResult.builder("2025-11-25",
                        McpSchema.ServerCapabilities.builder().tools(false).build(),
                        new McpSchema.Implementation(server, "1")).build());
    }
    @Test void exposesExactlyThreeWeatherToolsAndDeniesUnknownServerOrWrongConnection() {
        var filter = new McpPolicy("mcp").filter();
        var weather = connection("weather", "open-meteo-mcp-server");
        var names = List.of("openmeteo_search_locations", "openmeteo_get_forecast", "openmeteo_get_air_quality",
                "openmeteo_get_historical", "openmeteo_get_climate", "openmeteo_dataframe_drop");
        assertThat(names.stream().filter(name -> filter.test(weather, tool(name))))
                .containsExactly("openmeteo_search_locations", "openmeteo_get_forecast", "openmeteo_get_air_quality");
        for (String name : List.of("execute_shell", "read_file", "delete_database", "getRunningSummary")) {
            assertThat(filter.test(connection("running", "random-dangerous-server"), tool(name))).isFalse();
        }
        assertThat(filter.test(connection("weather", "running-mcp-server"), tool("getRunningSummary"))).isFalse();
    }
    @Test void localModeNeverExposesRemoteRunningDuplicates() {
        var running = connection("running", "running-mcp-server");
        assertThat(new McpPolicy("local").filter().test(running, tool("getRunningSummary"))).isFalse();
        assertThat(new McpPolicy("mcp").filter().test(running, tool("getRunningSummary"))).isTrue();
        assertThat(new McpPolicy("mcp").filter().test(running, tool("read_file"))).isFalse();
    }
}
