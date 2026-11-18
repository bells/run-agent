package cn.watsonzhu.runagent.reliability;

import java.time.Duration;
import java.util.*;
import cn.watsonzhu.runagent.config.MultiAgentProperties;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.model.MultiAgentRequest;
import cn.watsonzhu.runagent.multiagent.*;
import cn.watsonzhu.runagent.observability.ObservationTestSupport;
import cn.watsonzhu.runagent.performance.PerformanceTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Executes real graph failure paths with synthetic specialist contracts. */
public final class ReliabilityIntegrationScenarios {
    public record Result(String caseId, String expectedAction, String actualAction, int attemptCount, boolean passed, String reasonCode) { }
    public static List<Result> run() {
        List<Result> results = new ArrayList<>();
        for (Specialist failed : List.of(Specialist.RUNNING_ANALYST, Specialist.KNOWLEDGE_COACH, Specialist.WEATHER_ADVISOR)) {
            run(results, "partial-" + failed.name().toLowerCase(Locale.ROOT), "DEGRADE", () -> partial(failed));
        }
        run(results,"all-evidence-failed","FAIL_SAFE",ReliabilityIntegrationScenarios::allFailed);
        run(results,"critical-route-failed","FAIL_SAFE",()->critical(true));
        run(results,"critical-synthesis-failed","FAIL_SAFE",()->critical(false));
        return List.copyOf(results);
    }
    private static void run(List<Result> results,String id,String expected,java.util.function.IntSupplier action) {
        try { results.add(new Result(id,expected,expected,action.getAsInt(),true,"CONTRACT_VERIFIED")); }
        catch (RuntimeException | AssertionError failure) { results.add(new Result(id,expected,"FAILED",0,false,"CONTRACT_FAILED")); }
    }
    private static int partial(Specialist failed) {
        var env = new Environment();
        env.fail(failed);
        var result=env.coordinator().execute(env.request);
        String limitation=switch(failed) {
            case RUNNING_ANALYST -> "个人跑步分析本次不可用";
            case KNOWLEDGE_COACH -> "训练知识分析本次不可用";
            case WEATHER_ADVISOR -> "天气分析本次不可用";
            default -> throw new AssertionError();
        };
        assertThat(result.content()).contains(limitation);
        assertThat(env.meters.get("runagent.reliability.degraded").counter().count()).isEqualTo(1);
        verify(env.supervisor,times(1)).synthesize(argThat(state->state.status(failed)==SpecialistStatus.UNAVAILABLE && state.report(failed).isEmpty()));
        return result.delegationCount();
    }
    private static int allFailed() {
        var env=new Environment();
        for(var specialist:List.of(Specialist.RUNNING_ANALYST,Specialist.KNOWLEDGE_COACH,Specialist.WEATHER_ADVISOR)) env.fail(specialist);
        assertThatThrownBy(()->env.coordinator().execute(env.request)).isInstanceOfSatisfying(MultiAgentException.class,
                e->assertThat(e.kind()).isEqualTo(MultiAgentException.Kind.INSUFFICIENT_EVIDENCE));
        verify(env.supervisor,never()).synthesize(any());
        return 3;
    }
    private static int critical(boolean route) {
        var env=new Environment();
        if(route)when(env.supervisor.decideNext(any())).thenThrow(new ReliabilityFailure(FailureCategory.PERMANENT));
        else when(env.supervisor.synthesize(any())).thenThrow(new ReliabilityFailure(FailureCategory.TRANSIENT));
        assertThatThrownBy(()->env.coordinator().execute(env.request)).isInstanceOf(MultiAgentException.class);
        if(route)verify(env.running,never()).analyze(anyString(),anyString(),any(),any(),any());
        else verify(env.supervisor,times(1)).synthesize(any());
        return 1;
    }
    private static final class Environment {
        final SupervisorAgent supervisor=mock(SupervisorAgent.class);
        final RunningAnalystAgent running=mock(RunningAnalystAgent.class);
        final KnowledgeCoachAgent knowledge=mock(KnowledgeCoachAgent.class);
        final WeatherAdvisorAgent weather=mock(WeatherAdvisorAgent.class);
        final SimpleMeterRegistry meters=new SimpleMeterRegistry();
        final MultiAgentRequest request=new MultiAgentRequest("synthetic reliability question",null,null,"synthetic city");
        Environment() {
            when(running.isAvailable()).thenReturn(true);when(knowledge.isAvailable()).thenReturn(true);when(weather.isAvailable()).thenReturn(true);
            when(running.analyze(anyString(),anyString(),any(),any(),any())).thenReturn("synthetic running evidence");
            when(knowledge.analyze(anyString(),anyString(),any())).thenReturn("synthetic knowledge evidence");
            when(weather.analyze(anyString(),anyString(),any(),any())).thenReturn("synthetic weather evidence");
            when(supervisor.decideNext(any())).thenReturn(new SupervisorDecision(Specialist.RUNNING_ANALYST,"synthetic running"),
                new SupervisorDecision(Specialist.KNOWLEDGE_COACH,"synthetic knowledge"),new SupervisorDecision(Specialist.WEATHER_ADVISOR,"synthetic weather"));
            when(supervisor.synthesize(any())).thenReturn("synthetic partial advice");
        }
        void fail(Specialist agent) {
            var failure=new ReliabilityFailure(FailureCategory.DEPENDENCY_UNAVAILABLE);
            switch(agent) {
                case RUNNING_ANALYST -> when(running.analyze(anyString(),anyString(),any(),any(),any())).thenThrow(failure);
                case KNOWLEDGE_COACH -> when(knowledge.analyze(anyString(),anyString(),any())).thenThrow(failure);
                case WEATHER_ADVISOR -> when(weather.analyze(anyString(),anyString(),any(),any())).thenThrow(failure);
                default -> throw new AssertionError();
            }
        }
        MultiAgentCoordinator coordinator() {
            return new MultiAgentCoordinator(supervisor,running,knowledge,weather,new MultiAgentProperties(Duration.ofSeconds(10),3,2500,2000),
                ObservationTestSupport.noop(),PerformanceTestSupport.governance(),new ReliabilityExecutor(new ReliabilityProperties(true,null,null,null,null),meters));
        }
    }
}
