package cn.watsonzhu.runagent.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.webflux.autoconfigure.StreamableHttpWebFluxTransportAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

class WeatherProfileTest {
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=weather-mcp")
            .withUserConfiguration(StreamableHttpWebFluxTransportAutoConfiguration.class, cn.watsonzhu.runagent.config.WeatherMcpConfig.class)
            .withInitializer(ctx -> {
                try {
                    var sources = new YamlPropertySourceLoader().load("weather", new ClassPathResource("application-weather-mcp.yml"));
                    sources.forEach(source -> ctx.getEnvironment().getPropertySources().addLast(source));
                } catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
            });
    }
    @Test void fullHostedUrlBuildsTransportWithoutConnectingDuringConfiguration() {
        context().withPropertyValues("WEATHER_MCP_URL=https://weather.example/mcp")
            .run(ctx -> assertThat(ctx).hasNotFailed().hasBean("streamableHttpWebFluxClientTransports"));
    }
    @Test void configuredYamlDefaultWorksWithoutAnEnvironmentVariable() {
        context().run(ctx -> assertThat(ctx).hasNotFailed()
                .hasBean("streamableHttpWebFluxClientTransports"));
    }
    @Test void emptyEffectiveWeatherUrlFailsConfiguration() {
        context().withPropertyValues("spring.ai.mcp.client.streamable-http.connections.weather.url=",
                    "spring.ai.mcp.client.streamable-http.connections.weather.endpoint=/mcp")
            .run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test void effectiveWeatherUrlCannotBypassValidationWithASeparateValidEnvironmentVariable() {
        context().withPropertyValues("WEATHER_MCP_URL=https://weather.example/mcp",
                    "spring.ai.mcp.client.streamable-http.connections.weather.url=ftp://weather.example/mcp")
            .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test void runningAndWeatherProfilesKeepBothConnectionsAndRemoteRunningProvider() {
        new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=local,mcp,weather-mcp",
                    "WEATHER_MCP_URL=https://weather.example/mcp")
            .withUserConfiguration(StreamableHttpWebFluxTransportAutoConfiguration.class,
                    cn.watsonzhu.runagent.config.WeatherMcpConfig.class,
                    RunningProfileTest.PropertiesConfig.class)
            .withInitializer(ctx -> {
                try {
                    var loader = new YamlPropertySourceLoader();
                    for (String name : new String[] {"application.yml", "application-mcp.yml", "application-weather-mcp.yml"}) {
                        for (var source : loader.load(name, new ClassPathResource(name))) {
                            ctx.getEnvironment().getPropertySources().addFirst(source);
                        }
                    }
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException(exception);
                }
            })
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean(cn.watsonzhu.runagent.config.RunningToolProperties.class).provider()).isEqualTo("mcp");
                var connections = ctx.getBean(org.springframework.ai.mcp.client.common.autoconfigure.properties
                        .McpStreamableHttpClientProperties.class).getConnections();
                assertThat(connections).containsOnlyKeys("running", "weather");
                assertThat(connections.get("running").url()).isEqualTo("http://127.0.0.1:8090");
                assertThat(connections.get("running").endpoint()).isEqualTo("/mcp");
                assertThat(connections.get("weather").endpoint()).isEqualTo("https://weather.example/mcp");
            });
    }
}
