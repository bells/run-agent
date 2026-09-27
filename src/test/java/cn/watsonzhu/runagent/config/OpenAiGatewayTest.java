package cn.watsonzhu.runagent.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "AI_GATEWAY_API_KEY=test-key",
        "AI_GATEWAY_MODEL=gateway-test-model",
        "spring.ai.openai.max-retries=0"
})
class OpenAiGatewayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<String> REQUEST_PATH = new AtomicReference<>();
    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<JsonNode> REQUEST_BODY = new AtomicReference<>();
    private static final HttpServer SERVER = startServer();

    @Autowired private ChatModel chatModel;

    @DynamicPropertySource
    static void gatewayUrl(DynamicPropertyRegistry registry) {
        registry.add("AI_GATEWAY_BASE_URL", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void sendsChatCompletionToConfiguredCompatibleEndpoint() {
        assertThat(chatModel.call("ping")).isEqualTo("gateway reply");
        assertThat(REQUEST_PATH.get()).isEqualTo("/v1/chat/completions");
        assertThat(AUTHORIZATION.get()).isEqualTo("Bearer test-key");
        assertThat(REQUEST_BODY.get().path("model").asText()).isEqualTo("gateway-test-model");
        assertThat(REQUEST_BODY.get().path("messages").get(0).path("content").asText()).isEqualTo("ping");
    }

    @Test
    void streamsChatCompletionFromConfiguredCompatibleEndpoint() {
        assertThat(chatModel.stream("ping").collectList().block()).contains("gateway reply");
        assertThat(REQUEST_PATH.get()).isEqualTo("/v1/chat/completions");
        assertThat(REQUEST_BODY.get().path("stream").asBoolean()).isTrue();
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                REQUEST_PATH.set(exchange.getRequestURI().getPath());
                AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                REQUEST_BODY.set(JSON.readTree(exchange.getRequestBody()));
                if (REQUEST_BODY.get().path("stream").asBoolean()) {
                    byte[] response = ("data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\","
                            + "\"created\":0,\"model\":\"gateway-test-model\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"content\":\"gateway reply\"},\"finish_reason\":null}]}\n\n"
                            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, response.length);
                    try (var body = exchange.getResponseBody()) {
                        body.write(response);
                    }
                    return;
                }
                byte[] response = ("{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\","
                        + "\"created\":0,\"model\":\"gateway-test-model\",\"choices\":[{\"index\":0,"
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"gateway reply\"},"
                        + "\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(response);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start test gateway", exception);
        }
    }
}
