package cn.watsonzhu.runagent.performance;

import java.time.Duration;
import java.util.*;
import cn.watsonzhu.runagent.config.MultiAgentProperties;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.model.MultiAgentRequest;
import cn.watsonzhu.runagent.multiagent.*;
import cn.watsonzhu.runagent.observability.ObservationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import static cn.watsonzhu.runagent.performance.PerformanceCoreTest.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class PerformanceGraphTest {
    @Test void fourConsumedCallsStopDelegationAndFifthIsReservedForFinalSynthesis() {
        var b=budget(properties(PerformanceMode.BASELINE,5,30000));
        var supervisor=mock(SupervisorAgent.class);
        var running=mock(RunningAnalystAgent.class);var knowledge=mock(KnowledgeCoachAgent.class);var weather=mock(WeatherAdvisorAgent.class);
        when(running.isAvailable()).thenReturn(true);when(knowledge.isAvailable()).thenReturn(true);
        when(supervisor.decideNext(any())).thenAnswer(inv->{ExecutionScope.require().reserve(ModelStage.SUPERVISOR_ROUTE);return new SupervisorDecision(Specialist.RUNNING_ANALYST,"task");});
        when(running.analyze(anyString(),anyString(),any(),any(),any())).thenAnswer(inv->{
            for(int i=0;i<3;i++) {ExecutionScope.require().reserve(ModelStage.RUNNING_ANALYST);record(b,ModelStage.RUNNING_ANALYST,new DefaultUsage(10,2));}
            return "successful evidence";
        });
        when(supervisor.synthesize(any())).thenAnswer(inv->{ExecutionScope.require().reserve(ModelStage.SUPERVISOR_SYNTHESIS);return "safe answer";});
        var coordinator=new MultiAgentCoordinator(supervisor,running,knowledge,weather,new MultiAgentProperties(Duration.ofSeconds(10),3,2500,2000),ObservationTestSupport.noop(),new PerformanceGovernance(b.properties(),ObservationTestSupport.noop()));
        var result=coordinator.executeBudgeted(new MultiAgentRequest("safe question",null,null,null),b);
        assertThat(result.agentsUsed()).containsExactly("RUNNING_ANALYST");
        assertThat(b.modelCalls()).isEqualTo(5);
        verify(supervisor,times(1)).decideNext(any());verify(knowledge,never()).analyze(anyString(),anyString(),any());
        assertThat(ExecutionScope.current()).isNull();
    }
    @Test void exhaustionWithoutSuccessfulEvidenceFailsSafelyAndNeverSynthesizes() {
        var b=budget(properties(PerformanceMode.BASELINE,2,30000));
        var supervisor=mock(SupervisorAgent.class);var running=mock(RunningAnalystAgent.class);
        when(running.isAvailable()).thenReturn(true);
        when(supervisor.decideNext(any())).thenAnswer(inv->{ExecutionScope.require().reserve(ModelStage.SUPERVISOR_ROUTE);return new SupervisorDecision(Specialist.RUNNING_ANALYST,"task");});
        when(running.analyze(anyString(),anyString(),any(),any(),any())).thenAnswer(inv->{ExecutionScope.require().reserve(ModelStage.RUNNING_ANALYST);return "must not happen";});
        var coordinator=new MultiAgentCoordinator(supervisor,running,mock(KnowledgeCoachAgent.class),mock(WeatherAdvisorAgent.class),new MultiAgentProperties(Duration.ofSeconds(10),3,2500,2000),ObservationTestSupport.noop(),new PerformanceGovernance(b.properties(),ObservationTestSupport.noop()));
        assertThatThrownBy(()->coordinator.executeBudgeted(new MultiAgentRequest("safe",null,null,null),b)).isInstanceOfSatisfying(MultiAgentException.class,e->assertThat(e.kind()).isEqualTo(MultiAgentException.Kind.BUDGET_EXCEEDED));
        verify(supervisor,never()).synthesize(any());assertThat(ExecutionScope.current()).isNull();
    }
    @Test void snapshotContainsOnlySafeImmutableMetadataAndKnownSubtotals() {
        var b=budget(properties(PerformanceMode.BASELINE,16,30000));
        b.reserve(ModelStage.KNOWLEDGE_COACH); record(b,ModelStage.KNOWLEDGE_COACH,new DefaultUsage(10,2));
        b.stageFinished(ModelStage.KNOWLEDGE_COACH,Duration.ofMillis(5));
        var snapshot=PerformanceSnapshot.capture(b,new CostEstimator(b.properties().pricing()));
        assertThat(snapshot.usageComplete()).isTrue(); assertThat(snapshot.knownTotalTokens()).isEqualTo(12);
        assertThat(snapshot.stageBreakdown().get(ModelStage.KNOWLEDGE_COACH).modelCalls()).isOne();
        assertThat(snapshot.stageBreakdown().get(ModelStage.KNOWLEDGE_COACH).stageDuration()).isEqualTo(Duration.ofMillis(5));
        assertThatThrownBy(()->snapshot.stageBreakdown().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
