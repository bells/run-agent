package cn.watsonzhu.runagent.observability;

public final class ObservationTestSupport {
    private ObservationTestSupport() { }
    public static RunAgentObservation noop() {
        return new RunAgentObservation(io.micrometer.observation.ObservationRegistry.NOOP, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }
}
