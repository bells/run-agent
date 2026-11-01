package cn.watsonzhu.runagent.observability;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import cn.watsonzhu.runagent.exception.AgentLimitException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RunAgentObservationTest {
    @Test void agentRecordsOneFinalToolCountAndOnlySafeLowCardinalityTags() {
        var registry = TestObservationRegistry.create();
        var meters = new SimpleMeterRegistry();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        var observations = new RunAgentObservation(registry, meters);
        String id = UUID.randomUUID().toString();
        var calls = new AtomicInteger();
        assertThat(observations.agent(id, calls::get, () -> { calls.set(3); return "answer"; })).isEqualTo("answer");
        var summary = meters.get("runagent.agent.tool.calls").summary();
        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.totalAmount()).isEqualTo(3);
        registry.assertThat().hasObservationWithNameEqualTo("runagent.agent");
        assertThat(meters.get("runagent.agent").tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(meters.getMeters()).allSatisfy(m -> assertThat(m.getId().getTags().toString()).doesNotContain(id, "executionId", "conversationId", "question", "queryId"));
    }
    @Test void failureAndLimitStopObservationWithoutRawExceptionContent() {
        var registry = TestObservationRegistry.create();
        var meters = new SimpleMeterRegistry();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        var observations = new RunAgentObservation(registry, meters);
        assertThatThrownBy(() -> observations.agent("id", () -> 2, () -> { throw new AgentLimitException(); })).isInstanceOf(AgentLimitException.class);
        assertThat(meters.get("runagent.agent").tag("outcome", "limit_exceeded").timer().count()).isEqualTo(1);
        assertThatThrownBy(() -> observations.workflow("id", "COMBINED", () -> { throw new IllegalStateException("synthetic-secret"); })).isInstanceOf(IllegalStateException.class);
        assertThat(meters.get("runagent.workflow").tag("outcome", "error").timer().count()).isEqualTo(1);
        assertThat(registry.getCurrentObservation()).isNull();
    }
}
