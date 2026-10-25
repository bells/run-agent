package cn.watsonzhu.runagent.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/** Starter owns transports/lifecycle. Discovery runs once per connection, independently. */
public final class McpCatalog implements ToolCallbackProvider {
    private static final Logger log = LoggerFactory.getLogger(McpCatalog.class);
    private final ToolCallback[] callbacks;
    private final Map<String, Map<String, ToolCallback>> capabilities;

    public McpCatalog(List<McpSyncClient> clients, McpToolFilter filter, ToolContextToMcpMetaConverter converter) {
        List<ToolCallback> exposed = new ArrayList<>();
        Map<String, Map<String, ToolCallback>> accepted = new HashMap<>();
        for (var client : clients) {
            String title = client.getClientInfo().title();
            String connection = ("running".equals(title) || "weather".equals(title)) ? title : "unknown";
            if ("unknown".equals(connection)) {
                log.warn("mcp server=unknown status=DENIED exposed=0");
                continue;
            }
            try {
                var initialized = client.initialize();
                var discovered = new java.util.concurrent.atomic.AtomicInteger();
                var provider = SyncMcpToolCallbackProvider.builder().mcpClients(client)
                        .toolFilter((info, tool) -> { discovered.incrementAndGet(); return filter.test(info, tool); }).toolContextToMcpMetaConverter(converter).build();
                var tools = provider.getToolCallbacks();
                Map<String, ToolCallback> connectionTools = new HashMap<>();
                for (var callback : tools) {
                    exposed.add(new TracingToolCallback(callback, connection));
                    String originalName = callback instanceof SyncMcpToolCallback mcp
                            ? mcp.getOriginalToolName() : callback.getToolDefinition().name();
                    connectionTools.put(originalName, callback);
                }
                accepted.put(connection, Map.copyOf(connectionTools));
                log.info("mcp server={} version={} protocol={} status=READY discovered={} exposed={} tools={}", connection,
                        safeVersion(initialized.serverInfo().version()), safeVersion(initialized.protocolVersion()),
                        discovered.get(), tools.length, java.util.Arrays.stream(tools).map(t -> t.getToolDefinition().name()).toList());
            } catch (RuntimeException exception) {
                // Exception text can contain URLs, arguments or remote responses. Never log it.
                log.warn("mcp server={} status=UNAVAILABLE exposed=0", connection);
            }
        }
        this.callbacks = exposed.toArray(ToolCallback[]::new);
        this.capabilities = Map.copyOf(accepted);
    }
    private static String safeVersion(String version) {
        return version != null && version.matches("[A-Za-z0-9._-]{1,40}") ? version : "unknown";
    }
    @Override public ToolCallback[] getToolCallbacks() { return callbacks.clone(); }

    /** Only callbacks accepted during startup discovery are addressable here. No Agent trace is attached. */
    public Optional<ToolCallback> findTool(String connection, String toolName) {
        return Optional.ofNullable(capabilities.getOrDefault(connection, Map.of()).get(toolName));
    }
}
