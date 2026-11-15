package cn.watsonzhu.runagent.performance;

import java.time.Duration;
import java.util.*;
import cn.watsonzhu.runagent.config.PerformanceProperties;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.multiagent.MultiAgentExecutionControl;
import cn.watsonzhu.runagent.observability.RunAgentObservation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import static cn.watsonzhu.runagent.performance.PerformanceCoreTest.*;
import static org.assertj.core.api.Assertions.*;

class PerformancePrivacyTest {
    @Test void lateProviderCompletionRecordsFailedAttemptAndDeadlineMetricWithoutSensitiveContent() {
        var registry=ObservationRegistry.create();var meters=new SimpleMeterRegistry();
        var captured=new ArrayList<Observation.Context>();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context c){return true;}
            public void onStop(Observation.Context c){captured.add(c);}
        });
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("cn.watsonzhu.runagent.performance");
        var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();logs.start();logger.addAppender(logs);
        var props=properties(PerformanceMode.BASELINE,16,30000);
        var b=new ExecutionBudget("safe-id",new MultiAgentExecutionControl(Duration.ofMillis(50)),props);
        try {
            var model=new BudgetedChatModel(prompt->{
                java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
                return response(new org.springframework.ai.chat.metadata.DefaultUsage(10,2));
            },ModelStage.KNOWLEDGE_COACH,registry);
            try(var scope=ExecutionScope.open(b)) {
                assertThatThrownBy(()->model.call(new Prompt("PRIVATE_QUESTION_PROMPT_EVIDENCE"))).isInstanceOf(MultiAgentException.class);
            }
            var snapshot=new PerformanceGovernance(props,new RunAgentObservation(registry,meters)).finish(b);
            assertThat(snapshot.calls()).singleElement().satisfies(r->assertThat(r.status()).isEqualTo(ModelCallStatus.FAILED));
            assertThat(snapshot.budgetReasons()).contains(BudgetReason.DEADLINE);
            assertThat(meters.get("runagent.performance.budget.exhausted").tag("budgetReason","deadline").counter().count()).isOne();
            assertThat(meters.getMeters()).allSatisfy(m->assertThat(m.getId().getTags().toString()).doesNotContain("safe-id","configured-default","PRIVATE"));
            assertThat(captured).allSatisfy(c->{assertThat(c.getLowCardinalityKeyValues().toString()).doesNotContain("safe-id","PRIVATE","configured-default");assertThat(c.getError()).isNull();});
            assertThat(logs.list).allSatisfy(l->{assertThat(l.getFormattedMessage()).doesNotContain("PRIVATE");assertThat(l.getThrowableProxy()).isNull();});
            assertThat(registry.getCurrentObservation()).isNull();assertThat(ExecutionScope.current()).isNull();
        } finally {logger.detachAppender(logs);logs.stop();}
    }
}
