package cn.watsonzhu.runagent.reliability;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReliabilityIntegrationTest {
    @Test void partialAllAndCriticalFailuresFollowRealGraphPolicy() {
        assertThat(ReliabilityIntegrationScenarios.run()).hasSize(6).allSatisfy(result->assertThat(result.passed()).as(result.caseId()).isTrue());
    }
}
