package cn.watsonzhu.runagent.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import cn.watsonzhu.runagent.config.AgentProperties;
import cn.watsonzhu.runagent.model.AgentResponse;

import cn.watsonzhu.runagent.agent.RunAgentService;
import cn.watsonzhu.runagent.exception.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.reactive.server.WebTestClient;

class ControllerValidationTest {

    private LocalValidatorFactoryBean validator;
    private WebTestClient webTestClient;
    private RunAgentService service;

    @BeforeEach
    void setUp() {
        service = mock(RunAgentService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        MethodValidationPostProcessor methodValidation = new MethodValidationPostProcessor();
        methodValidation.setValidator(validator);
        methodValidation.afterPropertiesSet();
        ChatController chatController = (ChatController) methodValidation.postProcessAfterInitialization(
                new ChatController(service), "chatController");

        webTestClient = WebTestClient.bindToController(
                        chatController,
                        new IntentController(service),
                        new AgentController(service, new AgentProperties(Duration.ofMillis(30))))
                .controllerAdvice(new ApiExceptionHandler())
                .validator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void chatRejectsBlankMessage() {
        webTestClient.post()
                .uri("/api/chat")
                .bodyValue("{\"message\":\"   \"}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
                .jsonPath("$.message").isEqualTo("message: message must not be blank");
    }

    @Test
    void intentRejectsMissingMessage() {
        webTestClient.post()
                .uri("/api/intent")
                .bodyValue("{}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_REQUEST");
    }

    @Test
    void streamRejectsBlankMessage() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/chat/stream")
                        .queryParam("message", " ")
                        .build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_REQUEST");
    }

    @Test
    void agentRejectsBlankMessage() {
        webTestClient.post().uri("/api/agent")
                .bodyValue("{\"message\":\"   \"}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST");
    }

    @Test
    void agentReturnsExecutionMetadata() {
        when(service.agent("analyze"))
                .thenReturn(new AgentResponse("execution-1", 3, "result"));
        webTestClient.post().uri("/api/agent")
                .bodyValue("{\"message\":\"analyze\"}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.executionId").isEqualTo("execution-1")
                .jsonPath("$.toolCallCount").isEqualTo(3)
                .jsonPath("$.content").isEqualTo("result");
    }

    @Test
    void agentTimeoutHasSafeErrorBody() {
        when(service.agent("slow")).thenAnswer(invocation -> {
            Thread.sleep(200);
            return new AgentResponse("late", 0, "late");
        });
        webTestClient.post().uri("/api/agent")
                .bodyValue("{\"message\":\"slow\"}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody()
                .jsonPath("$.code").isEqualTo("AGENT_TIMEOUT");
    }
}
