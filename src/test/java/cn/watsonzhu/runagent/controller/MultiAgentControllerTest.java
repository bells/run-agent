package cn.watsonzhu.runagent.controller;

import java.time.Duration;
import java.util.List;
import cn.watsonzhu.runagent.config.MultiAgentProperties;
import cn.watsonzhu.runagent.exception.ApiExceptionHandler;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.model.MultiAgentResponse;
import cn.watsonzhu.runagent.multiagent.MultiAgentCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MultiAgentControllerTest {
    private final MultiAgentCoordinator coordinator = mock(MultiAgentCoordinator.class);
    private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    private WebTestClient client;
    @BeforeEach void prepare() {
        validator.afterPropertiesSet();
        client = client(Duration.ofSeconds(2));
    }
    @AfterEach void close() { validator.close(); }
    @Test void acceptsOptionalHintsAndExposesOnlyPublicContractOnBlockingBoundary() {
        when(coordinator.execute(any(), any())).thenAnswer(call -> {
            assertThat(Thread.currentThread().getName()).startsWith("boundedElastic");
            return new MultiAgentResponse("id", List.of("supervisor", "runningAnalyst", "supervisor", "synthesize"),
                    List.of("RUNNING_ANALYST"), 1, "answer");
        });
        post("{\"question\":\"q\"}").expectStatus().isOk().expectBody()
                .jsonPath("$.executionId").isEqualTo("id").jsonPath("$.delegationCount").isEqualTo(1)
                .jsonPath("$.delegationPath.length()").isEqualTo(4).jsonPath("$.content").isEqualTo("answer")
                .jsonPath("$.conversationId").doesNotExist().jsonPath("$.runningReport").doesNotExist()
                .jsonPath("$.sharedState").doesNotExist().jsonPath("$.prompt").doesNotExist()
                .jsonPath("$.weatherPayload").doesNotExist().jsonPath("$.knowledgeEvidence").doesNotExist();
    }
    @Test void invalidHttpInputFailsBeforeCoordinator() {
        for (String body : List.of("{}", "{\"question\":\" \"}", "{\"question\":\"" + "x".repeat(4001) + "\"}",
                "{\"question\":\"q\",\"location\":\"" + "x".repeat(201) + "\"}",
                "{\"question\":\"q\",\"startDate\":\"wrong\"}")) {
            post(body).expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("MULTI_AGENT_INVALID_REQUEST");
        }
        verifyNoInteractions(coordinator);
    }
    @ParameterizedTest @EnumSource(MultiAgentException.Kind.class)
    void domainErrorsHaveStableSafeCodes(MultiAgentException.Kind kind) {
        when(coordinator.execute(any(), any())).thenThrow(new MultiAgentException(kind));
        int status = switch (kind) {
            case INVALID_REQUEST -> 400;
            case TIMEOUT -> 504;
            case DEPENDENCY_UNAVAILABLE, INSUFFICIENT_EVIDENCE -> 503;
            case INVALID_SUPERVISOR_DECISION, EXECUTION_FAILED -> 502;
        };
        post("{\"question\":\"q\"}").expectStatus().isEqualTo(status).expectBody()
                .jsonPath("$.code").isEqualTo("MULTI_AGENT_" + kind.name())
                .jsonPath("$.cause").doesNotExist().jsonPath("$.stackTrace").doesNotExist();
    }
    @Test void httpDeadlineCancelsExecutionAndReturns504() {
        client = client(Duration.ofMillis(50));
        when(coordinator.execute(any(), any())).thenAnswer(call -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            return new MultiAgentResponse("late", List.of(), List.of(), 0, "late");
        });
        post("{\"question\":\"q\"}").expectStatus().isEqualTo(504).expectBody().jsonPath("$.code").isEqualTo("MULTI_AGENT_TIMEOUT");
    }
    @Test void lateFailureAfterHttpTimeoutDoesNotBecomeDroppedError() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var dropped = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        var dropObserved = new java.util.concurrent.CountDownLatch(1);
        reactor.core.publisher.Hooks.onErrorDropped(error -> { dropped.add(error); dropObserved.countDown(); });
        when(coordinator.execute(any(), any())).thenAnswer(call -> {
            entered.countDown();
            try {
                while (true) {
                    try { release.await(); break; } catch (InterruptedException ignored) { }
                }
                throw new MultiAgentException(MultiAgentException.Kind.EXECUTION_FAILED);
            } finally { finished.countDown(); }
        });
        var controller = new MultiAgentController(coordinator, new MultiAgentProperties(Duration.ofMillis(100), 3, 2500, 2000));
        try {
            reactor.test.StepVerifier.create(controller.advise(new cn.watsonzhu.runagent.model.MultiAgentRequest("q", null, null, null)))
                    .then(() -> {
                        try { assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                    })
                    .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(MultiAgentException.class,
                            e -> assertThat(e.kind()).isEqualTo(MultiAgentException.Kind.TIMEOUT)))
                    .verify(Duration.ofSeconds(3));
            release.countDown();
            assertThat(finished.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            dropObserved.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertThat(dropped).isEmpty();
        } finally { release.countDown(); reactor.core.publisher.Hooks.resetOnErrorDropped(); }
    }

    @Test void configurationRejectsInvalidLimitsAndReportBounds() {
        for (int count : List.of(0, 4)) assertThatThrownBy(() -> new MultiAgentProperties(Duration.ofSeconds(1), count, 2500, 2000)).isInstanceOf(IllegalArgumentException.class);
        for (int chars : List.of(499, 5001)) assertThatThrownBy(() -> new MultiAgentProperties(Duration.ofSeconds(1), 3, chars, 2000)).isInstanceOf(IllegalArgumentException.class);
        for (Duration value : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofHours(2)))
            assertThatThrownBy(() -> new MultiAgentProperties(value, 3, 2500, 2000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MultiAgentProperties(null, 3, 2500, 2000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MultiAgentProperties(Duration.ofSeconds(1), 3, 2500, 0)).isInstanceOf(IllegalArgumentException.class);
    }
    private WebTestClient client(Duration timeout) {
        return WebTestClient.bindToController(new MultiAgentController(coordinator, new MultiAgentProperties(timeout, 3, 2500, 2000)))
                .controllerAdvice(new ApiExceptionHandler()).validator(validator).build();
    }
    private WebTestClient.ResponseSpec post(String body) {
        return client.post().uri("/api/multi-agent/training-advice").header("Content-Type", "application/json").bodyValue(body).exchange();
    }
}
