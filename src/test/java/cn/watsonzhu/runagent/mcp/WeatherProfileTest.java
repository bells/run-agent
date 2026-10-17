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
    @Test void explicitlyEnabledWeatherWithoutUrlFailsConfiguration() {
        context().run(ctx -> assertThat(ctx).hasFailed());
    }
}
