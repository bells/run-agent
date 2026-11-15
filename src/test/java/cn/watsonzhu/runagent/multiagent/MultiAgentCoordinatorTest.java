package cn.watsonzhu.runagent.multiagent;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import cn.watsonzhu.runagent.config.MultiAgentProperties;
import cn.watsonzhu.runagent.controller.MultiAgentController;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.model.MultiAgentRequest;
import cn.watsonzhu.runagent.observability.ObservationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MultiAgentCoordinatorTest {
    private final SupervisorAgent supervisor = mock(SupervisorAgent.class);
    private final RunningAnalystAgent running = mock(RunningAnalystAgent.class);
    private final KnowledgeCoachAgent knowledge = mock(KnowledgeCoachAgent.class);
    private final WeatherAdvisorAgent weather = mock(WeatherAdvisorAgent.class);
    private final MultiAgentRequest request = new MultiAgentRequest("synthetic {question}",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 30), "synthetic city");
    private MultiAgentProperties properties = new MultiAgentProperties(Duration.ofSeconds(5), 3, 2500, 2000);

    @BeforeEach void prepare() {
        when(running.isAvailable()).thenReturn(true);
        when(knowledge.isAvailable()).thenReturn(true);
        when(weather.isAvailable()).thenReturn(true);
        when(running.analyze(anyString(), anyString(), any(), any(), any())).thenReturn("running report");
        when(knowledge.analyze(anyString(), anyString(), any())).thenReturn("knowledge report");
        when(weather.analyze(anyString(), anyString(), any(), any())).thenReturn("weather report");
        when(supervisor.synthesize(any())).thenReturn("unified advice");
    }

    @ParameterizedTest @EnumSource(value = Specialist.class, names = "FINISH", mode = EnumSource.Mode.EXCLUDE)
    void actualCycleExecutesOnlySelectedSpecialist(Specialist selected) {
        when(supervisor.decideNext(any())).thenReturn(new SupervisorDecision(selected, "subtask"), finish());
        var result = coordinator().execute(request);
        assertThat(result.agentsUsed()).containsExactly(selected.name());
        assertThat(result.delegationPath()).containsExactly("supervisor", selected.nodeName(), "supervisor", "synthesize");
        assertThat(result.delegationCount()).isEqualTo(1);
        assertThat(result.content()).isEqualTo("unified advice");
        switch (selected) {
            case RUNNING_ANALYST -> { verifyNoInteractionsExceptAvailability(knowledge, weather); }
            case KNOWLEDGE_COACH -> { verifyNoInteractionsExceptAvailability(running, weather); }
            case WEATHER_ADVISOR -> { verifyNoInteractionsExceptAvailability(running, knowledge); }
            default -> throw new AssertionError();
        }
    }

    @Test void threeSpecialistsFormRealCycleAndGuardSkipsFourthRoutingCall() {
        when(supervisor.decideNext(any())).thenReturn(new SupervisorDecision(Specialist.RUNNING_ANALYST, "running"),
                new SupervisorDecision(Specialist.KNOWLEDGE_COACH, "knowledge"),
                new SupervisorDecision(Specialist.WEATHER_ADVISOR, "weather"));
        var result = coordinator().execute(request);
        assertThat(result.delegationPath()).containsExactly("supervisor", "runningAnalyst", "supervisor",
                "knowledgeCoach", "supervisor", "weatherAdvisor", "supervisor", "synthesize");
        assertThat(result.delegationCount()).isEqualTo(3);
        verify(supervisor, times(3)).decideNext(any());
    }

    @Test void limitTwoSynthesizesWithoutAskingRouterForThirdDelegation() {
        properties = new MultiAgentProperties(Duration.ofSeconds(5), 2, 2500, 2000);
        when(supervisor.decideNext(any())).thenReturn(new SupervisorDecision(Specialist.RUNNING_ANALYST, "running"),
                new SupervisorDecision(Specialist.KNOWLEDGE_COACH, "knowledge"));
        assertThat(coordinator().execute(request).delegationCount()).isEqualTo(2);
        verify(supervisor, times(2)).decideNext(any());
        verify(weather, never()).analyze(anyString(), anyString(), any(), any());
    }

    @Test void duplicateDecisionFailsBeforeSecondSpecialistCall() {
        when(supervisor.decideNext(any())).thenReturn(new SupervisorDecision(Specialist.RUNNING_ANALYST, "running"));
        assertKind(() -> coordinator().execute(request), MultiAgentException.Kind.INVALID_SUPERVISOR_DECISION);
        verify(running, times(1)).analyze(anyString(), anyString(), any(), any(), any());
    }

    @Test void malformedOrOversizedDecisionsFailSafely() {
        for (var decision : List.of(new SupervisorDecision(null, "task"),
                new SupervisorDecision(Specialist.RUNNING_ANALYST, " "),
                new SupervisorDecision(Specialist.RUNNING_ANALYST, "x".repeat(2001)))) {
            when(supervisor.decideNext(any())).thenReturn(decision);
            assertKind(() -> coordinator().execute(request), MultiAgentException.Kind.INVALID_SUPERVISOR_DECISION);
        }
        when(supervisor.decideNext(any())).thenReturn(null);
        assertKind(() -> coordinator().execute(request), MultiAgentException.Kind.INVALID_SUPERVISOR_DECISION);
    }

    @Test void unavailableSelectionIsRejectedWithoutInvokingAgent() {
        when(weather.isAvailable()).thenReturn(false);
        when(supervisor.decideNext(any())).thenReturn(new SupervisorDecision(Specialist.WEATHER_ADVISOR, "weather"));
        assertKind(() -> coordinator().execute(request), MultiAgentException.Kind.INVALID_SUPERVISOR_DECISION);
        verify(weather, never()).analyze(anyString(), anyString(), any(), any());
    }

    @ParameterizedTest @EnumSource(value = Specialist.class, names = "FINISH", mode = EnumSource.Mode.EXCLUDE)
    void individualDependencyFailuresReturnToSupervisor(Specialist failed) {
        switch (failed) {
            case RUNNING_ANALYST -> when(running.analyze(anyString(), anyString(), any(), any(), any()))
                    .thenThrow(new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE));
            case KNOWLEDGE_COACH -> when(knowledge.analyze(anyString(), anyString(), any()))
                    .thenThrow(new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE));
            case WEATHER_ADVISOR -> when(weather.analyze(anyString(), anyString(), any(), any()))
                    .thenThrow(new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE));
            default -> throw new AssertionError();
        }
        Specialist successful = failed == Specialist.RUNNING_ANALYST ? Specialist.KNOWLEDGE_COACH : Specialist.RUNNING_ANALYST;
        when(supervisor.decideNext(any())).thenReturn(new SupervisorDecision(failed, "failing"),
                new SupervisorDecision(successful, "working"), finish());
        when(supervisor.synthesize(any())).thenAnswer(call -> {
            MultiAgentState state = call.getArgument(0);
            assertThat(state.status(failed)).isEqualTo(SpecialistStatus.UNAVAILABLE);
            assertThat(state.report(failed)).isEmpty();
            return "partial advice";
        });
        assertThat(coordinator().execute(request).content()).isEqualTo("partial advice");
    }

    @Test void noSuccessfulEvidenceDoesNotInvokeSynthesis() {
        when(supervisor.decideNext(any())).thenReturn(new SupervisorDecision(Specialist.RUNNING_ANALYST, "task"), finish());
        when(running.analyze(anyString(), anyString(), any(), any(), any())).thenThrow(new IllegalStateException("secret"));
        assertKind(() -> coordinator().execute(request), MultiAgentException.Kind.INSUFFICIENT_EVIDENCE);
        verify(supervisor, never()).synthesize(any());
    }

    @Test void allThreeFailedSpecialistsExhaustEvidenceWithoutSynthesisOrRetries() {
        when(supervisor.decideNext(any())).thenReturn(new SupervisorDecision(Specialist.RUNNING_ANALYST, "running"),
                new SupervisorDecision(Specialist.KNOWLEDGE_COACH, "knowledge"), new SupervisorDecision(Specialist.WEATHER_ADVISOR, "weather"));
        when(running.analyze(anyString(), anyString(), any(), any(), any())).thenThrow(new IllegalStateException("private running failure"));
        when(knowledge.analyze(anyString(), anyString(), any())).thenThrow(new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE));
        when(weather.analyze(anyString(), anyString(), any(), any())).thenThrow(new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE));
        assertKind(() -> coordinator().execute(request), MultiAgentException.Kind.INSUFFICIENT_EVIDENCE);
        verify(supervisor, times(3)).decideNext(any());
        verify(running, times(1)).analyze(anyString(), anyString(), any(), any(), any());
        verify(knowledge, times(1)).analyze(anyString(), anyString(), any());
        verify(weather, times(1)).analyze(anyString(), anyString(), any(), any());
        verify(supervisor, never()).synthesize(any());
    }

    @Test void absentCapabilitiesDoNotStartRouterOrSynthesis() {
        when(running.isAvailable()).thenReturn(false);
        when(knowledge.isAvailable()).thenReturn(false);
        when(weather.isAvailable()).thenReturn(false);
        assertKind(() -> coordinator().execute(request), MultiAgentException.Kind.INSUFFICIENT_EVIDENCE);
        verifyNoInteractions(supervisor);
    }

    @Test void graphStoresOnlyHardBoundedSpecialistReports() {
        when(supervisor.decideNext(any())).thenReturn(new SupervisorDecision(Specialist.RUNNING_ANALYST, "task"), finish());
        when(running.analyze(anyString(), anyString(), any(), any(), any())).thenReturn("x".repeat(3000));
        when(supervisor.synthesize(any())).thenAnswer(call -> {
            MultiAgentState state = call.getArgument(0);
            assertThat(state.report(Specialist.RUNNING_ANALYST)).hasSize(2500);
            assertThat(state.data()).doesNotContainKeys("rawEvidence", "toolContext", "knowledgeEvidence", "deadlineNanos", "prompt");
            return "bounded advice";
        });
        assertThat(coordinator().execute(request).content()).isEqualTo("bounded advice");
    }

    @Test void finishWithoutEvidenceFailsAndAllowsNullTask() {
        when(supervisor.decideNext(any())).thenReturn(finish());
        assertKind(() -> coordinator().execute(request), MultiAgentException.Kind.INSUFFICIENT_EVIDENCE);
        verify(supervisor, never()).synthesize(any());
    }

    @Test void supervisorAndSynthesisFailuresAreWholeRequestFailures() {
        when(supervisor.decideNext(any())).thenThrow(new IllegalStateException("secret provider payload"));
        assertKind(() -> coordinator().execute(request), MultiAgentException.Kind.EXECUTION_FAILED);
        doReturn(new SupervisorDecision(Specialist.RUNNING_ANALYST, "task"), finish()).when(supervisor).decideNext(any());
        when(supervisor.synthesize(any())).thenThrow(new IllegalStateException("secret"));
        assertKind(() -> coordinator().execute(request), MultiAgentException.Kind.EXECUTION_FAILED);
    }

    @Test void emptySynthesisAndBlankReportsCannotPassValidation() {
        when(supervisor.decideNext(any())).thenReturn(new SupervisorDecision(Specialist.RUNNING_ANALYST, "task"), finish());
        when(supervisor.synthesize(any())).thenReturn(" ");
        assertKind(() -> coordinator().execute(request), MultiAgentException.Kind.EXECUTION_FAILED);
        when(running.analyze(anyString(), anyString(), any(), any(), any())).thenReturn(" ");
        assertKind(() -> coordinator().execute(request), MultiAgentException.Kind.INSUFFICIENT_EVIDENCE);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void synthesisLogsSafeLifecycleOnSuccessAndFailure(boolean success) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MultiAgentCoordinator.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        when(supervisor.decideNext(any())).thenReturn(new SupervisorDecision(Specialist.RUNNING_ANALYST, "task"), finish());
        if (success) when(supervisor.synthesize(any())).thenReturn("private-synthesis-answer");
        else when(supervisor.synthesize(any())).thenThrow(new IllegalStateException("private-provider-failure"));
        try {
            if (success) coordinator().execute(request);
            else assertKind(() -> coordinator().execute(request), MultiAgentException.Kind.EXECUTION_FAILED);
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
        var messages = events.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anyMatch(message -> message.contains("phase=SYNTHESIZE status=STARTED"))
                .anyMatch(message -> message.contains("phase=SYNTHESIZE status=" + (success ? "SUCCESS" : "FAILED")));
        assertThat(messages).allSatisfy(message -> assertThat(message).doesNotContain("private-synthesis-answer", "private-provider-failure"));
        assertThat(events.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test void invalidDatesReuseInclusiveRangeValidationBeforeRouter() {
        for (var item : List.of(new MultiAgentRequest("question", request.startDate(), null, null),
                new MultiAgentRequest("question", request.endDate(), request.startDate(), null),
                new MultiAgentRequest("question", request.startDate(), request.startDate().plusDays(366), null))) {
            assertKind(() -> coordinator().execute(item), MultiAgentException.Kind.INVALID_REQUEST);
        }
        verify(supervisor, never()).decideNext(any());
    }

    @Test void cancellationStopsAfterInterruptIgnoringSpecialistReturns() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        when(supervisor.decideNext(any())).thenReturn(new SupervisorDecision(Specialist.RUNNING_ANALYST, "task"), finish());
        when(running.analyze(anyString(), anyString(), any(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            while (true) {
                try { release.await(); break; } catch (InterruptedException ignored) { }
            }
            exited.countDown();
            return "late evidence";
        });
        var controller = new MultiAgentController(coordinator(), properties);
        try {
            StepVerifier.create(controller.advise(request)).then(() -> await(entered)).thenCancel().verify();
        } finally { release.countDown(); }
        assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
        verify(supervisor, times(1)).decideNext(any());
        verify(supervisor, never()).synthesize(any());
    }

    @Test void globalDeadlineStopsLaterRoutingEvenWithoutAnInterrupt() throws Exception {
        when(supervisor.decideNext(any())).thenReturn(new SupervisorDecision(Specialist.RUNNING_ANALYST, "task"), finish());
        when(running.analyze(anyString(), anyString(), any(), any(), any())).thenAnswer(call -> {
            Thread.sleep(80);
            return "late evidence";
        });
        assertKind(() -> coordinator().execute(request, new MultiAgentExecutionControl(Duration.ofMillis(50))),
                MultiAgentException.Kind.TIMEOUT);
        verify(supervisor, times(1)).decideNext(any());
        verify(supervisor, never()).synthesize(any());
    }

    @Test void cancellationClosesBusinessObservationsAfterLateProviderReturns() throws Exception {
        var registry = io.micrometer.observation.ObservationRegistry.create();
        var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        registry.observationConfig().observationHandler(new io.micrometer.core.instrument.observation.DefaultMeterObservationHandler(meters));
        var observations = new cn.watsonzhu.runagent.observability.RunAgentObservation(registry, meters);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(supervisor.decideNext(any())).thenReturn(new SupervisorDecision(Specialist.RUNNING_ANALYST, "task"));
        when(running.analyze(anyString(), anyString(), any(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            await(release);
            return "late report";
        });
        var coordinator = new MultiAgentCoordinator(supervisor, running, knowledge, weather, properties, observations, cn.watsonzhu.runagent.performance.PerformanceTestSupport.governance());
        var control = new MultiAgentExecutionControl(properties.timeout());
        var future = CompletableFuture.supplyAsync(() -> {
            try { return coordinator.execute(request, control); }
            finally { assertThat(registry.getCurrentObservation()).isNull(); }
        });
        try { await(entered); control.cancel(); }
        finally { release.countDown(); }
        assertThatThrownBy(future::join).hasCauseInstanceOf(MultiAgentException.class);
        assertThat(meters.get("runagent.multi_agent").tag("outcome", "timeout").timer().count()).isEqualTo(1);
        assertThat(meters.get("runagent.multi_agent.specialist").tags("specialist", "running", "outcome", "timeout").timer().count()).isEqualTo(1);
        assertThat(meters.get("runagent.multi_agent.delegations").summary().count()).isEqualTo(1);
        assertThat(meters.get("runagent.multi_agent.delegations").summary().totalAmount()).isEqualTo(1);
        verify(supervisor, never()).synthesize(any());
    }

    @Test void cancelledProviderFailureIsClassifiedAsTimeoutInRootAndStage() {
        var registry = io.micrometer.observation.ObservationRegistry.create();
        var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        registry.observationConfig().observationHandler(new io.micrometer.core.instrument.observation.DefaultMeterObservationHandler(meters));
        var observations = new cn.watsonzhu.runagent.observability.RunAgentObservation(registry, meters);
        var control = new MultiAgentExecutionControl(properties.timeout());
        when(supervisor.decideNext(any())).thenAnswer(call -> {
            control.cancel();
            throw new IllegalStateException("private interrupted provider");
        });
        var coordinator = new MultiAgentCoordinator(supervisor, running, knowledge, weather, properties, observations, cn.watsonzhu.runagent.performance.PerformanceTestSupport.governance());
        assertKind(() -> coordinator.execute(request, control), MultiAgentException.Kind.TIMEOUT);
        assertThat(meters.get("runagent.multi_agent").tag("outcome", "timeout").timer().count()).isEqualTo(1);
        assertThat(meters.get("runagent.multi_agent.specialist").tags("specialist", "supervisor_route", "outcome", "timeout").timer().count()).isEqualTo(1);
        assertThat(registry.getCurrentObservation()).isNull();
        verify(supervisor, never()).synthesize(any());
    }

    @Test void concurrentRequestsHaveIndependentStateAndIds() {
        when(supervisor.decideNext(any())).thenAnswer(call -> {
            MultiAgentState state = call.getArgument(0);
            return state.completedAgents().isEmpty() ? new SupervisorDecision(Specialist.RUNNING_ANALYST, state.question()) : finish();
        });
        when(running.analyze(anyString(), anyString(), any(), any(), any())).thenAnswer(call -> call.getArgument(0));
        when(supervisor.synthesize(any())).thenAnswer(call -> ((MultiAgentState) call.getArgument(0)).report(Specialist.RUNNING_ANALYST));
        var coordinator = coordinator();
        var futures = java.util.stream.IntStream.range(0, 8).mapToObj(i -> CompletableFuture.supplyAsync(
                () -> coordinator.execute(new MultiAgentRequest("request-" + i, null, null, null)))).toList();
        var results = futures.stream().map(CompletableFuture::join).toList();
        assertThat(results.stream().map(r -> r.executionId()).distinct()).hasSize(8);
        for (int i = 0; i < 8; i++) assertThat(results.get(i).content()).isEqualTo("request-" + i);
    }

    private MultiAgentCoordinator coordinator() {
        return new MultiAgentCoordinator(supervisor, running, knowledge, weather, properties, ObservationTestSupport.noop(), cn.watsonzhu.runagent.performance.PerformanceTestSupport.governance());
    }
    private static SupervisorDecision finish() { return new SupervisorDecision(Specialist.FINISH, null); }
    private static void assertKind(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, MultiAgentException.Kind kind) {
        assertThatThrownBy(action).isInstanceOfSatisfying(MultiAgentException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
    private static void verifyNoInteractionsExceptAvailability(Object... agents) {
        for (Object agent : agents) assertThat(mockingDetails(agent).getInvocations()).allMatch(i -> i.getMethod().getName().equals("isAvailable"));
    }
}
