package cn.watsonzhu.runagent.config;

import java.util.ArrayList;
import java.util.List;
import cn.watsonzhu.runagent.mcp.McpCatalog;
import cn.watsonzhu.runagent.mcp.McpPolicy;
import cn.watsonzhu.runagent.tool.RunningTools;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunningToolProperties.class)
public class McpConfig {
    @Bean McpToolFilter mcpToolFilter(RunningToolProperties properties) {
        return new McpPolicy(properties.provider()).filter();
    }
    @Bean ToolContextToMcpMetaConverter toolContextToMcpMetaConverter() {
        return ToolContextToMcpMetaConverter.noOp();
    }
    @Bean("remoteTools")
    @ConditionalOnProperty(name = "spring.ai.mcp.client.enabled", havingValue = "true")
    McpCatalog remoteTools(List<McpSyncClient> clients, McpToolFilter filter, ToolContextToMcpMetaConverter converter) {
        return new McpCatalog(clients, filter, converter);
    }
    @Bean("agentTools")
    ToolCallbackProvider agentTools(RunningToolProperties properties, ObjectProvider<RunningTools> local,
            @Qualifier("remoteTools") ObjectProvider<ToolCallbackProvider> remote, Environment environment) {
        if ("mcp".equals(properties.provider())
                && !environment.matchesProfiles("mcp")) {
            throw new IllegalStateException("RUNNING_TOOL_PROVIDER=mcp requires the mcp profile");
        }
        if ("mcp".equals(properties.provider()) && remote.getIfAvailable() == null) {
            throw new IllegalStateException("RUNNING_TOOL_PROVIDER=mcp requires the MCP client to be enabled");
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        if ("local".equals(properties.provider())) {
            callbacks.addAll(List.of(ToolCallbacks.from(local.getObject())));
        }
        remote.ifAvailable(provider -> callbacks.addAll(List.of(provider.getToolCallbacks())));
        var snapshot = callbacks.toArray(ToolCallback[]::new);
        return () -> snapshot.clone();
    }
}
