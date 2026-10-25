package cn.watsonzhu.runagent.mcp;

import cn.watsonzhu.runagent.config.RunningDataProperties;
import cn.watsonzhu.runagent.config.RunningToolProperties;
import cn.watsonzhu.runagent.service.RunningDataService;
import cn.watsonzhu.runagent.tool.RunningTools;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RunningProfileTest {
    @Test void mcpProfileSelectsRemoteRunningToolsWithoutAnEnvironmentVariable() {
        context(true).run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean(RunningTools.class).doesNotHaveBean(RunningDataService.class);
            var properties = ctx.getBean(RunningToolProperties.class);
            assertThat(properties.provider()).isEqualTo("mcp");
            assertThat(new McpPolicy(properties.provider()).filter().test(
                    McpPolicyTest.connection("running", "running-mcp-server"),
                    McpPolicyTest.tool("getRunningSummary"))).isTrue();
        });
    }

    @Test void ordinaryStartupKeepsLocalToolsAndDoesNotEnableMcp() {
        context(false).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(RunningTools.class).hasSingleBean(RunningDataService.class);
            assertThat(ctx.getBean(RunningToolProperties.class).provider()).isEqualTo("local");
            assertThat(ctx.getEnvironment().getProperty("spring.ai.mcp.client.enabled", Boolean.class)).isFalse();
        });
    }

    @Test void explicitLocalProviderRemainsAvailableWithMcpProfile() {
        context(true).withPropertyValues("RUNNING_TOOL_PROVIDER=local").run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(RunningTools.class).hasSingleBean(RunningDataService.class);
            assertThat(new McpPolicy(ctx.getBean(RunningToolProperties.class).provider()).filter().test(
                    McpPolicyTest.connection("running", "running-mcp-server"),
                    McpPolicyTest.tool("getRunningSummary"))).isFalse();
        });
    }

    private ApplicationContextRunner context(boolean mcp) {
        return new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class, RunningTools.class, RunningDataService.class)
                .withBean(tools.jackson.databind.ObjectMapper.class, JsonMapper::new)
                .withInitializer(ctx -> {
                    try {
                        var loader = new YamlPropertySourceLoader();
                        for (String name : mcp
                                ? new String[] {"application.yml", "application-mcp.yml"}
                                : new String[] {"application.yml"}) {
                            for (var source : loader.load(name, new ClassPathResource(name))) {
                                ctx.getEnvironment().getPropertySources().addFirst(source);
                            }
                        }
                    } catch (java.io.IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({RunningToolProperties.class, RunningDataProperties.class})
    static class PropertiesConfig {}
}
