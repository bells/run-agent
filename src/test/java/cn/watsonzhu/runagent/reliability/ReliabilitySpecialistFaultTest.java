package cn.watsonzhu.runagent.reliability;
import org.junit.jupiter.api.Test;
class ReliabilitySpecialistFaultTest {
    @Test void weatherTimeoutAndRagFailureReachRealPartialSynthesis() throws Exception { ReliabilitySpecialistFaultScenarios.run(); }
}
