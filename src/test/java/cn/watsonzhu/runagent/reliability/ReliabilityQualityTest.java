package cn.watsonzhu.runagent.reliability;
import org.junit.jupiter.api.Test;
class ReliabilityQualityTest {
    @Test void sameSevenCasesPreserveQualityAndHealthyCallCounts() throws Exception { ReliabilityQualityScenarios.run(); }
}
