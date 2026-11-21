package cn.watsonzhu.runagent.marathon;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "management.server.port=0",
        "spring.ai.openai.api-key=synthetic-key",
        "run-agent.private-chat.enabled=true",
        "run-agent.private-chat.token=0123456789abcdef0123456789abcdef0123456789abcdef",
        "run-agent.public-api.enabled=true",
        "run-agent.marathon.enabled=true"
})
class MarathonChatHttpTest {
    @LocalServerPort int port;
    @MockitoBean ChatModel model;

    @Test
    void anonymousChatReadsAnOfficialSeedThroughTheExistingHttpContractWithoutAModel() {
        var client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        var question = Map.of("message", "2025 年重庆半马女子前三名成绩");
        client.post().uri("/api/v1/chat").headers(headers -> headers.setBearerAuth("invalid"))
                .bodyValue(question).exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/chat").bodyValue(question).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.requestId").isNotEmpty()
                .jsonPath("$.content").value((String content) -> assertThat(content).contains(
                        "2025", "刘君慧", "01:13:37", "叶瑞", "01:19:47", "方可馨", "www.cqbn.gov.cn"));
        verify(model, never()).call(any(Prompt.class));
    }
}
