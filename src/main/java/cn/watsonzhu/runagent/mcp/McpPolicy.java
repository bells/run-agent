package cn.watsonzhu.runagent.mcp;

import java.util.Set;
import org.springframework.ai.mcp.McpToolFilter;

/** One fail-closed discovery policy, keyed by configured connection and advertised identity. */
public final class McpPolicy {
    private static final Set<String> RUNNING = Set.of("getRunningSummary", "getRecentRuns", "getPersonalBest");
    private static final Set<String> WEATHER = Set.of("openmeteo_search_locations", "openmeteo_get_forecast",
            "openmeteo_get_air_quality");
    private final boolean remoteRunning;

    public McpPolicy(String provider) {
        if (!Set.of("local", "mcp").contains(provider)) {
            throw new IllegalArgumentException("RUNNING_TOOL_PROVIDER must be local or mcp");
        }
        this.remoteRunning = "mcp".equals(provider);
    }

    public McpToolFilter filter() {
        return (connection, tool) -> {
            if (connection == null || connection.clientInfo() == null || connection.initializeResult() == null
                    || connection.initializeResult().serverInfo() == null) {
                return false;
            }
            String name = connection.initializeResult().serverInfo().name();
            String configured = connection.clientInfo().title();
            return switch (configured == null ? "" : configured) {
                case "running" -> remoteRunning && "running-mcp-server".equals(name) && RUNNING.contains(tool.name());
                case "weather" -> "open-meteo-mcp-server".equals(name) && WEATHER.contains(tool.name());
                default -> false;
            };
        };
    }
}
