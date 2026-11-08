package cn.watsonzhu.runagent.multiagent;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.observability.RunAgentObservation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MultiAgentObservationTest {
    @Test void oneFinalDelegationSampleAndFixedStageTagsExcludeSensitiveText() {
        var registry = ObservationRegistry.create();
        var meters = new SimpleMeterRegistry();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        var observations = new RunAgentObservation(registry, meters);
        var count = new AtomicInteger();
        observations.multiAgent("synthetic-id", count::get, () -> {
            for (String agent : java.util.List.of("running", "knowledge", "weather")) {
                observations.multiAgentSpecialist("synthetic-id", agent, () -> {
                    count.incrementAndGet();
                    return "secret report";
                });
            }
            return "secret final answer";
        });
        var summary = meters.get("runagent.multi_agent.delegations").summary();
        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.totalAmount()).isEqualTo(3);
        assertThat(meters.get("runagent.multi_agent").tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(meters.find("runagent.multi_agent.specialist").timers()).hasSize(3);
        assertThat(meters.getMeters().stream().flatMap(m -> m.getId().getTags().stream()))
                .allMatch(t -> !t.getValue().contains("synthetic-id") && !t.getValue().contains("secret"));
        assertThat(registry.getCurrentObservation()).isNull();
    }

    @Test void unavailableAndTimeoutOutcomesCloseScopesAndExportOnlySafeErrors() {
        var registry = ObservationRegistry.create();
        var meters = new SimpleMeterRegistry();
        var errors = new java.util.ArrayList<Throwable>();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context context) { return true; }
            public void onStop(Observation.Context context) { if (context.getError() != null) errors.add(context.getError()); }
        });
        var observations = new RunAgentObservation(registry, meters);
        assertThatThrownBy(() -> observations.multiAgentSpecialist("id", "weather", () -> {
            throw new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE);
        })).isInstanceOf(MultiAgentException.class);
        assertThatThrownBy(() -> observations.multiAgent("id", () -> 1, () -> {
            throw new MultiAgentException(MultiAgentException.Kind.TIMEOUT);
        })).isInstanceOf(MultiAgentException.class);
        assertThat(meters.get("runagent.multi_agent.specialist").tags("specialist", "weather", "outcome", "unavailable").timer().count()).isEqualTo(1);
        assertThat(meters.get("runagent.multi_agent").tag("outcome", "timeout").timer().count()).isEqualTo(1);
        assertThat(errors).allSatisfy(e -> { assertThat(e.getMessage()).isEqualTo("Operation failed"); assertThat(e.getCause()).isNull(); });
        assertThat(registry.getCurrentObservation()).isNull();
        assertThatThrownBy(() -> observations.multiAgentSpecialist("id", "untrusted-dynamic-tag", () -> "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
