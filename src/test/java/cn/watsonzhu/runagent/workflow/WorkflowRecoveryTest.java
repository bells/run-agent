package cn.watsonzhu.runagent.workflow;

import java.time.Duration;
import java.util.List;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import cn.watsonzhu.runagent.reliability.recovery.WorkflowRecoveryService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import cn.watsonzhu.runagent.model.TrainingAnalysisRequest;
import org.springframework.ai.document.Document;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowRecoveryTest {
    @Test void resumesActualCheckpointWithoutRepeatingSuccessfulRunningNode() {
        assertThat(WorkflowRecoveryScenarios.verifyResume()).isEqualTo(1);
    }

    @ParameterizedTest @EnumSource(WorkflowMode.class)
    void checkpointEnabledSupportsAllExistingModes(WorkflowMode mode) {
        var fixture = new WorkflowRecoveryScenarios.Fixture(true);
        String id = fixture.recovery.create(new TrainingAnalysisRequest("request-secret-question", mode,
                mode == WorkflowMode.KNOWLEDGE_ONLY ? null : WorkflowRecoveryScenarios.Fixture.START,
                mode == WorkflowMode.KNOWLEDGE_ONLY ? null : WorkflowRecoveryScenarios.Fixture.END));
        assertThat(fixture.recovery.run(id).mode()).isEqualTo(mode);
        assertThat(fixture.recovery.checkpointStates(id)).isNotEmpty();
    }

    @Test void cancelledControlBeforeFirstCheckpointLeavesExecutionEligibleForFreshStart() {
        var fixture = new WorkflowRecoveryScenarios.Fixture(true);
        String id = fixture.recovery.create(WorkflowRecoveryScenarios.Fixture.request());
        var cancelled = new WorkflowExecutionControl(Duration.ofSeconds(5));
        cancelled.cancel();
        assertThatThrownBy(() -> fixture.recovery.run(id, cancelled))
                .isInstanceOf(cn.watsonzhu.runagent.exception.WorkflowException.class);
        assertThat(fixture.recovery.checkpointStates(id)).isEmpty();
        assertThat(fixture.recovery.run(id).content()).isEqualTo("synthetic analysis");
        assertThat(fixture.runningCalls).hasValue(1);
    }

    @Test void expiredControlBeforeFirstCheckpointLeavesExecutionEligibleForFreshStart() {
        var fixture = new WorkflowRecoveryScenarios.Fixture(true);
        String id = fixture.recovery.create(WorkflowRecoveryScenarios.Fixture.request());
        assertThatThrownBy(() -> fixture.recovery.run(id, new WorkflowExecutionControl(Duration.ZERO)))
                .isInstanceOf(cn.watsonzhu.runagent.exception.WorkflowException.class);
        assertThat(fixture.recovery.checkpointStates(id)).isEmpty();
        assertThat(fixture.recovery.run(id).content()).isEqualTo("synthetic analysis");
        assertThat(fixture.runningCalls).hasValue(1);
    }

    @Test void runnerFailureBeforeCreatingAnyCheckpointLeavesExecutionEligibleForFreshStart() {
        var recovery = new WorkflowRecoveryService(true);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        recovery.bind((id, request, control, resume) -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("safe pre-graph failure");
            return new cn.watsonzhu.runagent.model.TrainingAnalysisResponse(id, WorkflowMode.COMBINED,
                    List.of(), "synthetic analysis");
        }, Duration.ofSeconds(5));
        String id = recovery.create(WorkflowRecoveryScenarios.Fixture.request());
        assertThatThrownBy(() -> recovery.run(id)).isInstanceOf(IllegalStateException.class);
        assertThat(recovery.checkpointStates(id)).isEmpty();
        assertThat(recovery.run(id).content()).isEqualTo("synthetic analysis");
        assertThat(attempts).hasValue(2);
    }

    @Test void recoveryMetricsUseOnlyFixedPhaseAndOutcomeTagsIncludingFailures() {
        var meters = new SimpleMeterRegistry();
        var recovery = new WorkflowRecoveryService(true, 64, Duration.ofMinutes(30), meters);
        var failFirst = new java.util.concurrent.atomic.AtomicBoolean(true);
        recovery.bind((id, request, control, resume) -> {
            if (failFirst.getAndSet(false)) throw new IllegalStateException("private-failure");
            return new cn.watsonzhu.runagent.model.TrainingAnalysisResponse(id, WorkflowMode.COMBINED,
                    List.of("validateInput"), "synthetic analysis");
        }, Duration.ofSeconds(5));
        String id = recovery.create(WorkflowRecoveryScenarios.Fixture.request());
        assertThatThrownBy(() -> recovery.run(id)).isInstanceOf(IllegalStateException.class);
        recovery.run(id);
        recovery.resume(id);
        assertThat(meters.get("runagent.reliability.recovery").tags("phase", "START", "outcome", "FAILURE").counter().count()).isEqualTo(1);
        assertThat(meters.get("runagent.reliability.recovery").tags("phase", "RESUME", "outcome", "SUCCESS").counter().count()).isEqualTo(1);
        assertThat(meters.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isIn("phase", "outcome")));
    }

    @Test void allActualCheckpointStatesExcludeRuntimeAndSensitiveEvidence() {
        var fixture = new WorkflowRecoveryScenarios.Fixture(true);
        String id = fixture.recovery.create(WorkflowRecoveryScenarios.Fixture.request());
        fixture.recovery.run(id);
        assertThat(fixture.recovery.checkpointStates(id)).allSatisfy(state -> {
            assertThat(state.keySet()).isSubsetOf("executionId", "mode", "startDate", "endDate", "runningSummary", "executedNodes", "analysisStatus");
            assertThat(state.toString()).doesNotContain("request-secret-question", "raw-secret-chunk", "synthetic analysis",
                    "deadlineNanos", "ToolContext", "prompt", "apiKey", "Authorization", "latitude", "longitude");
        });
    }

    @Test void analysisFailureResumesWithEphemeralEvidenceWithoutRepeatingCompletedRetrieval() {
        var fixture = new WorkflowRecoveryScenarios.Fixture(true);
        fixture.modelFailures.set(1);
        String id = fixture.recovery.create(WorkflowRecoveryScenarios.Fixture.request());
        assertThatThrownBy(() -> fixture.recovery.run(id)).isInstanceOf(cn.watsonzhu.runagent.exception.WorkflowException.class);
        assertThat(fixture.recovery.resume(id).executedNodes()).containsExactly(
                "validateInput", "fetchRunningData", "retrieveKnowledge", "analyze", "validateOutput");
        verify(fixture.search, times(1)).retrieveDocuments(anyString(), anyString());
        assertThat(fixture.runningCalls).hasValue(1);
        assertThat(fixture.modelCalls).hasValue(2);
    }

    @Test void disabledFeatureDoesNotCreateCheckpointsAndKeepsExistingExecuteContract() {
        var fixture = new WorkflowRecoveryScenarios.Fixture(false);
        assertThat(fixture.workflow.execute(WorkflowRecoveryScenarios.Fixture.request()).content()).isEqualTo("synthetic analysis");
        assertThatThrownBy(() -> fixture.recovery.create(WorkflowRecoveryScenarios.Fixture.request()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Workflow checkpoint recovery is disabled");
    }

    @Test void concurrentResumeIsRejectedBeforeAnyDuplicateNodeCall() throws Exception {
        var fixture = new WorkflowRecoveryScenarios.Fixture(true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(fixture.search.retrieveDocuments(anyString(), anyString())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
            return List.of(new Document("raw-secret-chunk"));
        });
        String id = fixture.recovery.create(WorkflowRecoveryScenarios.Fixture.request());
        var first = CompletableFuture.supplyAsync(() -> fixture.recovery.run(id));
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> fixture.recovery.resume(id)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Workflow execution is already active");
            assertThatThrownBy(() -> fixture.recovery.forget(id)).isInstanceOf(IllegalStateException.class);
        } finally { release.countDown(); }
        assertThat(first.get(3, TimeUnit.SECONDS).content()).isEqualTo("synthetic analysis");
        assertThat(fixture.runningCalls).hasValue(1);
    }

    @Test void boundedSessionsCanBeExplicitlyReleasedTogetherWithCheckpoints() {
        var fixture = new WorkflowRecoveryScenarios.Fixture(true, 1, Duration.ofMinutes(30));
        String id = fixture.recovery.create(WorkflowRecoveryScenarios.Fixture.request());
        fixture.recovery.run(id);
        assertThatThrownBy(() -> fixture.recovery.create(WorkflowRecoveryScenarios.Fixture.request()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Workflow recovery capacity reached");
        fixture.recovery.forget(id);
        assertThatThrownBy(() -> fixture.recovery.resume(id)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.recovery.create(WorkflowRecoveryScenarios.Fixture.request())).isNotEqualTo(id);
    }

    @Test void expiredSessionCannotResumeAndFreesCapacity() throws Exception {
        var fixture = new WorkflowRecoveryScenarios.Fixture(true, 1, Duration.ofMillis(1));
        String id = fixture.recovery.create(WorkflowRecoveryScenarios.Fixture.request());
        Thread.sleep(10);
        assertThatThrownBy(() -> fixture.recovery.resume(id)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.recovery.create(WorkflowRecoveryScenarios.Fixture.request())).isNotEqualTo(id);
    }
}
