package cn.watsonzhu.runagent.controller;

import static org.mockito.Mockito.mock;

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

    @BeforeEach
    void setUp() {
        RunAgentService service = mock(RunAgentService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        MethodValidationPostProcessor methodValidation = new MethodValidationPostProcessor();
        methodValidation.setValidator(validator);
        methodValidation.afterPropertiesSet();
        ChatController chatController = (ChatController) methodValidation.postProcessAfterInitialization(
                new ChatController(service), "chatController");

        webTestClient = WebTestClient.bindToController(
                        chatController,
                        new IntentController(service))
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
}
