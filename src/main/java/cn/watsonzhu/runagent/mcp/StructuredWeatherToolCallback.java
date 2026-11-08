package cn.watsonzhu.runagent.mcp;

import java.util.Map;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Spring AI 2.0.1 drops structuredContent; preserve it using the same SDK client and accepted schema. */
public final class StructuredWeatherToolCallback implements ToolCallback {
    private static final JsonMapper JSON = new JsonMapper();
    private final McpSyncClient client;
    private final SyncMcpToolCallback definition;

    public StructuredWeatherToolCallback(McpSyncClient client, SyncMcpToolCallback definition) {
        this.client = client;
        this.definition = definition;
    }
    public String originalToolName() { return definition.getOriginalToolName(); }
    @Override public ToolDefinition getToolDefinition() { return definition.getToolDefinition(); }
    @Override public ToolMetadata getToolMetadata() { return definition.getToolMetadata(); }
    @Override public String call(String input) { return call(input, null); }
    @Override public String call(String input, ToolContext ignored) {
        try {
            var parsed = JSON.readTree(input);
            if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException();
            Map<String, Object> arguments = JSON.convertValue(parsed, new TypeReference<>() { });
            var result = client.callTool(McpSchema.CallToolRequest.builder(originalToolName())
                    .arguments(arguments).meta(Map.of()).build());
            if (result == null || Boolean.TRUE.equals(result.isError())) throw new IllegalStateException();
            return JSON.writeValueAsString(result.structuredContent() == null ? result.content() : result.structuredContent());
        } catch (RuntimeException exception) {
            // Provider errors may contain payloads. Existing outer trace wrappers map this fixed failure safely.
            throw new IllegalStateException("Weather MCP tool unavailable");
        }
    }
}
