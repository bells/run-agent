package cn.watsonzhu.runagent.controller;

import java.time.Duration;
import java.util.List;
import cn.watsonzhu.runagent.config.WorkflowProperties;
import cn.watsonzhu.runagent.exception.ApiExceptionHandler;
import cn.watsonzhu.runagent.exception.WorkflowException;
import cn.watsonzhu.runagent.model.TrainingAnalysisRequest;
import cn.watsonzhu.runagent.model.TrainingAnalysisResponse;
import cn.watsonzhu.runagent.workflow.TrainingAnalysisWorkflow;
import cn.watsonzhu.runagent.workflow.WorkflowMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TrainingWorkflowControllerTest {
    private final TrainingAnalysisWorkflow workflow = mock(TrainingAnalysisWorkflow.class);
    private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    private WebTestClient client;

    @BeforeEach void prepare() {
        validator.afterPropertiesSet();
        client = WebTestClient.bindToController(new TrainingWorkflowController(workflow, new WorkflowProperties(Duration.ofSeconds(2))))
                .controllerAdvice(new ApiExceptionHandler()).validator(validator).build();
    }
    @AfterEach void close() { validator.close(); }

    @ParameterizedTest @EnumSource(WorkflowMode.class)
    void acceptsExplicitModesAndReturnsOnlyPublicContract(WorkflowMode mode) {
        when(workflow.execute(any(), any())).thenAnswer(call -> {
            assertThat(Thread.currentThread().getName()).startsWith("boundedElastic");
            var request = call.getArgument(0, TrainingAnalysisRequest.class);
            assertThat(request.mode()).isEqualTo(mode);
            return new TrainingAnalysisResponse("execution", mode, List.of("validateInput", "analyze", "validateOutput"), "answer");
        });
        post("{\"question\":\"q\",\"mode\":\"" + mode + "\",\"startDate\":\"2026-09-25\",\"endDate\":\"2026-10-24\"}")
                .expectStatus().isOk().expectBody()
                .jsonPath("$.executionId").isEqualTo("execution")
                .jsonPath("$.mode").isEqualTo(mode.name())
                .jsonPath("$.content").isEqualTo("answer")
                .jsonPath("$.executedNodes.length()").isEqualTo(3)
                .jsonPath("$.rawState").doesNotExist().jsonPath("$.runningSummary").doesNotExist()
                .jsonPath("$.knowledgeEvidence").doesNotExist().jsonPath("$.prompt").doesNotExist()
                .jsonPath("$.conversationId").doesNotExist();
    }

    @Test void rejectsInvalidHttpInputBeforeGraphInvocation() {
        for (String body : List.of("{}", "{\"question\":\"q\"}",
                "{\"question\":\" \",\"mode\":\"RUNNING_ONLY\"}",
                "{\"question\":\"" + "q".repeat(4001) + "\",\"mode\":\"RUNNING_ONLY\"}",
                "{\"question\":\"q\",\"mode\":\"AUTO\"}",
                "{\"question\":\"q\",\"mode\":\"RUNNING_ONLY\",\"startDate\":\"invalid-date\"}")) {
            post(body).expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("WORKFLOW_INVALID_REQUEST");
        }
        verifyNoInteractions(workflow);
    }

    @ParameterizedTest @EnumSource(WorkflowException.Kind.class)
    void mapsDomainFailuresToStableSafeErrors(WorkflowException.Kind kind) {
        when(workflow.execute(any(), any())).thenThrow(new WorkflowException(kind));
        int status = switch (kind) {
            case INVALID_REQUEST -> 400;
            case TIMEOUT -> 504;
            case DEPENDENCY_UNAVAILABLE -> 503;
            case EXECUTION_FAILED -> 502;
        };
        post("{\"question\":\"q\",\"mode\":\"COMBINED\"}")
                .expectStatus().isEqualTo(status).expectBody().jsonPath("$.code").isEqualTo("WORKFLOW_" + kind)
                .jsonPath("$.stackTrace").doesNotExist().jsonPath("$.cause").doesNotExist();
    }

    @Test void shortApplicationDeadlineReturns504() {
        client = WebTestClient.bindToController(new TrainingWorkflowController(workflow, new WorkflowProperties(Duration.ofMillis(50))))
                .controllerAdvice(new ApiExceptionHandler()).validator(validator).build();
        when(workflow.execute(any(), any())).thenAnswer(call -> {
            Thread.sleep(300);
            return new TrainingAnalysisResponse("late", WorkflowMode.KNOWLEDGE_ONLY, List.of(), "late");
        });
        post("{\"question\":\"q\",\"mode\":\"KNOWLEDGE_ONLY\"}")
                .expectStatus().isEqualTo(504).expectBody().jsonPath("$.code").isEqualTo("WORKFLOW_TIMEOUT");
    }

    @Test void workflowTimeoutMustBePositive() {
        for (Duration value : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThatThrownBy(() -> new WorkflowProperties(value)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new WorkflowProperties(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private WebTestClient.ResponseSpec post(String body) {
        return client.post().uri("/api/workflows/training-analysis").header("Content-Type", "application/json")
                .bodyValue(body).exchange();
    }
}
