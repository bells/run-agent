package cn.watsonzhu.runagent.performance;

public final class PerformanceTestSupport {
    private PerformanceTestSupport() { }
    public static cn.watsonzhu.runagent.config.PerformanceProperties properties() {
        return PerformanceCoreTest.properties(PerformanceMode.BASELINE, 16, 30000);
    }
    public static PerformanceGovernance governance() { return new PerformanceGovernance(properties(), cn.watsonzhu.runagent.observability.ObservationTestSupport.noop()); }
}
