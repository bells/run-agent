package cn.watsonzhu.runagent.observability;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class ObservabilityConfigurationTest {
    @Test void defaultConfigurationDisablesNetworkExportAndSensitiveAiContent() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
            var env = context.getEnvironment();
            for (String key : List.of("management.tracing.export.enabled", "management.otlp.metrics.export.enabled", "management.logging.export.enabled",
                "spring.ai.chat.client.observations.log-prompt", "spring.ai.chat.client.observations.log-completion", "spring.ai.chat.observations.log-prompt",
                "spring.ai.chat.observations.log-completion", "spring.ai.chat.observations.include-error-logging", "spring.ai.tools.observations.include-content",
                "spring.ai.vectorstore.observations.log-query-response")) assertThat(env.getProperty(key, Boolean.class)).as(key).isFalse();
            assertThat(env.getProperty("management.server.address")).isEqualTo("127.0.0.1");
            assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
        });
    }
}
