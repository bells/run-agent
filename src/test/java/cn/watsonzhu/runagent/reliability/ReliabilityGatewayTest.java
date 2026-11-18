package cn.watsonzhu.runagent.reliability;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ReliabilityGatewayTest {
    @ParameterizedTest @ValueSource(ints={429,503,400,401,403,200})
    void sdkRetriesStayZeroAndApplicationCountsEveryHttpAttempt(int status) throws Exception {
        ReliabilityGatewayScenarios.verify(status,true);
    }
    @Test void disabledProtectionLeavesSdkWithExactlyOneRequest() throws Exception {
        ReliabilityGatewayScenarios.verify(429,false);
    }
}
