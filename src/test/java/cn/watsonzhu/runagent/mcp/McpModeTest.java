package cn.watsonzhu.runagent.mcp;

import cn.watsonzhu.runagent.config.McpConfig;
import cn.watsonzhu.runagent.config.RunningDataProperties;
import cn.watsonzhu.runagent.service.RunningDataService;
import cn.watsonzhu.runagent.tool.RunningTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class McpModeTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(McpConfig.class, RunningTools.class, RunningDataService.class)
            .withBean(RunningDataProperties.class, () -> new RunningDataProperties(""))
            .withBean(tools.jackson.databind.ObjectMapper.class, JsonMapper::new);

    @Test void defaultLocalModeProvidesExactlyThreeLocalToolsWithoutMcpClient() {
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(RunningTools.class).hasSingleBean(RunningDataService.class);
            assertThat(ctx.getBean("agentTools", ToolCallbackProvider.class).getToolCallbacks()).hasSize(3);
            assertThat(ctx).doesNotHaveBean("remoteTools");
        });
    }
    @Test void mcpModeDoesNotCreateLocalDataOrDuplicateTools() {
        context.withBean("remoteTools", ToolCallbackProvider.class, () -> () -> new org.springframework.ai.tool.ToolCallback[0])
                .withPropertyValues("run-agent.running-tools.provider=mcp", "spring.profiles.active=mcp")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().doesNotHaveBean(RunningTools.class).doesNotHaveBean(RunningDataService.class);
                    assertThat(ctx.getBean("agentTools", ToolCallbackProvider.class).getToolCallbacks()).isEmpty();
                });
    }
    @Test void missingProfileAndInvalidProviderFailClearly() {
        context.withPropertyValues("run-agent.running-tools.provider=mcp").run(ctx -> assertThat(ctx).hasFailed());
        context.withPropertyValues("run-agent.running-tools.provider=mcp", "spring.profiles.active=mcp")
                .run(ctx -> assertThat(ctx).hasFailed());
        context.withPropertyValues("run-agent.running-tools.provider=typo").run(ctx -> assertThat(ctx).hasFailed());
    }
}
