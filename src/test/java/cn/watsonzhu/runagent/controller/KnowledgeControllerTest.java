package cn.watsonzhu.runagent.controller;

import java.time.Duration;
import java.util.List;
import cn.watsonzhu.runagent.config.RagProperties;
import cn.watsonzhu.runagent.exception.ApiExceptionHandler;
import cn.watsonzhu.runagent.exception.KnowledgeBaseUnavailableException;
import cn.watsonzhu.runagent.exception.AiRequestException;
import cn.watsonzhu.runagent.model.KnowledgeAnswerResponse;
import cn.watsonzhu.runagent.rag.KnowledgeQaService;
import cn.watsonzhu.runagent.rag.KnowledgeSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class KnowledgeControllerTest {
    private final KnowledgeQaService qa = mock(KnowledgeQaService.class);
    private final KnowledgeSearchService search = mock(KnowledgeSearchService.class);
    private final RagProperties properties = new RagProperties(false, "", "unused.json", false,
            800, 350, 10, 5000, 5, 0.5, Duration.ofSeconds(2));

    @Test void validatesBothPayloadsBeforeCallingServices() {
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            var client = WebTestClient.bindToController(new KnowledgeController(qa, properties),
                    new KnowledgeSearchController(search, properties)).controllerAdvice(new ApiExceptionHandler())
                    .validator(validator).build();
            for (String route : List.of("ask", "search")) {
                client.post().uri("/api/knowledge/" + route).header("Content-Type", "application/json")
                        .bodyValue("{}").exchange().expectStatus().isBadRequest()
                        .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST");
            }
            client.post().uri("/api/knowledge/ask").bodyValue(java.util.Map.of("question", "x".repeat(2001)))
                    .exchange().expectStatus().isBadRequest();
            verifyNoInteractions(qa, search);
        }
    }

    @Test void returnsSafeUnavailableAndProviderErrors() {
        var client = WebTestClient.bindToController(new KnowledgeController(qa, properties))
                .controllerAdvice(new ApiExceptionHandler()).build();
        when(qa.ask("test")).thenThrow(new KnowledgeBaseUnavailableException("Knowledge base is not enabled."));
        client.post().uri("/api/knowledge/ask").bodyValue(java.util.Map.of("question", "test")).exchange()
                .expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("KNOWLEDGE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("Knowledge base is not enabled.");
        doThrow(new AiRequestException("Knowledge model invocation failed",
                new IllegalStateException("private provider response"))).when(qa).ask("test");
        client.post().uri("/api/knowledge/ask").bodyValue(java.util.Map.of("question", "test")).exchange()
                .expectStatus().isEqualTo(502).expectBody().jsonPath("$.message")
                .isEqualTo("The AI service is temporarily unavailable");
    }

    @Test void executesBlockingServiceOutsideEventLoopAndReturnsTypedAnswer() {
        when(qa.ask("轻松跑")).thenAnswer(invocation -> {
            assertThat(Thread.currentThread().getName()).startsWith("boundedElastic");
            return new KnowledgeAnswerResponse("query-1", "自创训练说明");
        });
        var client = WebTestClient.bindToController(new KnowledgeController(qa, properties)).build();
        client.post().uri("/api/knowledge/ask").bodyValue(java.util.Map.of("question", "轻松跑")).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.queryId").isEqualTo("query-1")
                .jsonPath("$.content").isEqualTo("自创训练说明");
    }

    @Test void searchControllerIsRegisteredOnlyInLocalProfile() {
        for (boolean local : new boolean[] {false, true}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                if (local) context.getEnvironment().setActiveProfiles("local");
                context.registerBean(KnowledgeSearchService.class, () -> search);
                context.registerBean(RagProperties.class, () -> properties);
                context.register(KnowledgeSearchController.class);
                context.refresh();
                assertThat(context.getBeansOfType(KnowledgeSearchController.class).size()).isEqualTo(local ? 1 : 0);
            }
        }
    }

    @Test void requestTimeoutProducesSafe503() {
        var shortTimeout = new RagProperties(false, "", "unused.json", false,
                800, 350, 10, 5000, 5, 0.5, Duration.ofMillis(20));
        when(qa.ask("slow")).thenAnswer(invocation -> {
            Thread.sleep(500);
            return new KnowledgeAnswerResponse("query-1", "late");
        });
        var client = WebTestClient.bindToController(new KnowledgeController(qa, shortTimeout))
                .controllerAdvice(new ApiExceptionHandler()).build();
        client.post().uri("/api/knowledge/ask").bodyValue(java.util.Map.of("question", "slow")).exchange()
                .expectStatus().isEqualTo(503).expectBody().jsonPath("$.message").isEqualTo("Knowledge request timed out.");
    }
}
