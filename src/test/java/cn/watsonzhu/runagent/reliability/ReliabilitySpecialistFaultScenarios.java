package cn.watsonzhu.runagent.reliability;

import java.time.Duration;
import java.util.*;
import cn.watsonzhu.runagent.evaluation.*;
import cn.watsonzhu.runagent.performance.PerformanceTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static cn.watsonzhu.runagent.evaluation.EvaluationModels.*;
import static org.assertj.core.api.Assertions.*;

/** Faults travel through real Specialist clients, tracing callbacks, tool loop and graph. */
public final class ReliabilitySpecialistFaultScenarios {
    public record Result(String caseId,int dependencyAttempts,boolean passed) { }
    public static List<Result> run() throws Exception {
        List<Result> results=new ArrayList<>();
        try(var fixture=new EvaluationFixtures()) {
            var scripts=fixture.cases("multi-agent-model-scripts.json",MultiAgentScript.class).stream().collect(java.util.stream.Collectors.toMap(MultiAgentScript::id,s->s));
            var cases=fixture.cases("multi-agent-cases.json",MultiAgentCase.class).stream().collect(java.util.stream.Collectors.toMap(MultiAgentCase::id,s->s));
            for(String role:List.of("weather","knowledge")) {
                String id="ma-"+role+"-failure";
                var executor=new ReliabilityExecutor(new ReliabilityProperties(true,null,null,null,null),new SimpleMeterRegistry(),()->0.5,delay->{});
                RuntimeException fault=role.equals("weather")?new RuntimeException(new java.util.concurrent.TimeoutException("PRIVATE_TIMEOUT"))
                        :new IllegalStateException("PRIVATE_RAG_FAILURE");
                var scenario=new MultiAgentScenario(fixture,scripts.get(id),PerformanceTestSupport.properties(),null,Duration.ofSeconds(10),executor,Map.of(role,fault));
                var response=scenario.execute(cases.get(id));
                assertThat(scenario.successful()).isEqualTo(Set.copyOf(cases.get(id).expectedSuccessfulAgents()));
                assertThat(response.content()).contains(role.equals("weather")?"天气分析本次不可用":"训练知识分析本次不可用");
                assertThat(scenario.dependencyAttempts(role)).isEqualTo(role.equals("weather")?2:1);
                assertThat(response.content()).doesNotContain("PRIVATE_TIMEOUT","PRIVATE_RAG_FAILURE");
                results.add(new Result(role.equals("weather")?"weather-timeout-retry-degrade":"rag-failure-no-retry-partial",scenario.dependencyAttempts(role),true));
            }
        }
        return List.copyOf(results);
    }
}
