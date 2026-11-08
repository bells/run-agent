package cn.watsonzhu.runagent.observability;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class ObservabilityConfigurationTest {
    @Test void configuredModelDoesNotRetryProviderRequestsByDefault() throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().close();
            byte[] body = "{\"error\":{\"message\":\"synthetic-provider-failure\",\"type\":\"server_error\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(cn.watsonzhu.runagent.RunAgentApplication.class)
                    .withPropertyValues("AI_GATEWAY_API_KEY=synthetic-key", "run-agent.rag.enabled=false", "spring.ai.mcp.client.enabled=false",
                            "AI_GATEWAY_BASE_URL=http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThatThrownBy(() -> context.getBean(org.springframework.ai.openai.OpenAiChatModel.class)
                                .call(new org.springframework.ai.chat.prompt.Prompt("synthetic question"))).isInstanceOf(RuntimeException.class);
                        assertThat(requests.get()).isEqualTo(1);
                    });
        } finally { server.stop(0); }
    }

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
