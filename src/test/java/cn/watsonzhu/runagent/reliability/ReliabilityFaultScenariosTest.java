package cn.watsonzhu.runagent.reliability;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReliabilityFaultScenariosTest {
    @Test void offlineFaultVerificationMeasuresBoundedCallsAndSafeActions() {
        var results=ReliabilityFaultScenarios.run();
        assertThat(results).hasSizeGreaterThanOrEqualTo(9).allSatisfy(r->{
            assertThat(r.passed()).as(r.caseId()).isTrue();
            assertThat(r.reasonCode()).isEqualTo("EXPECTED_ACTION");
            assertThat(r.actualAction()).isEqualTo(r.expectedAction());
        });
        assertThat(results).filteredOn(r->r.caseId().equals("llm-429")).singleElement().satisfies(r->assertThat(r.attemptCount()).isEqualTo(2));
        assertThat(results).filteredOn(r->r.caseId().equals("llm-400")).singleElement().satisfies(r->assertThat(r.attemptCount()).isOne());
    }
}
