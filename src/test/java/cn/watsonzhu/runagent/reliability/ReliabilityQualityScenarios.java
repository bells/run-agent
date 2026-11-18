package cn.watsonzhu.runagent.reliability;

import java.time.Duration;
import java.util.*;
import cn.watsonzhu.runagent.evaluation.*;
import cn.watsonzhu.runagent.performance.PerformanceTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static cn.watsonzhu.runagent.evaluation.EvaluationModels.*;
import static org.assertj.core.api.Assertions.*;

/** Same seven committed cases, independent fresh registries and the same performance budget. */
public final class ReliabilityQualityScenarios {
    public record Result(String caseId,int disabledModelCalls,int enabledModelCalls,boolean passed) { }
    public static List<Result> run() throws Exception {
        List<Result> results=new ArrayList<>();
        try(var fixtures=new EvaluationFixtures()) {
            var scripts=fixtures.cases("multi-agent-model-scripts.json",MultiAgentScript.class).stream()
                .collect(java.util.stream.Collectors.toMap(MultiAgentScript::id,item->item));
            for(var item:fixtures.cases("multi-agent-cases.json",MultiAgentCase.class)) {
                var disabled=new MultiAgentScenario(fixtures,scripts.get(item.id()),PerformanceTestSupport.properties(),null,Duration.ofSeconds(10));
                var enabled=new MultiAgentScenario(fixtures,scripts.get(item.id()),PerformanceTestSupport.properties(),null,Duration.ofSeconds(10),
                    new ReliabilityExecutor(new ReliabilityProperties(true,null,null,null,null),new SimpleMeterRegistry()));
                var original=disabled.execute(item);var protectedResponse=enabled.execute(item);
                assertThat(protectedResponse.agentsUsed()).containsExactlyElementsOf(original.agentsUsed());
                assertThat(enabled.successful()).isEqualTo(Set.copyOf(item.expectedSuccessfulAgents()));
                assertThat(enabled.isolated()).isTrue();assertThat(enabled.toolContract()).isTrue();
                assertThat(enabled.snapshot().modelCalls()).isEqualTo(disabled.snapshot().modelCalls());
                results.add(new Result(item.id(),disabled.snapshot().modelCalls(),enabled.snapshot().modelCalls(),true));
            }
        }
        assertThat(results).hasSize(7);
        return List.copyOf(results);
    }
}
