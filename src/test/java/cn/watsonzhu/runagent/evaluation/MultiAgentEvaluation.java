package cn.watsonzhu.runagent.evaluation;

import java.time.Duration;
import java.util.*;
import static cn.watsonzhu.runagent.evaluation.EvaluationModels.*;

/** Same real graph/client/tool/retrieval fixture used by performance comparisons. */
final class MultiAgentEvaluation {
    private MultiAgentEvaluation() { }
    static List<CaseResult> run(EvaluationFixtures fixture) {
        var results=new ArrayList<CaseResult>();
        var scripts=fixture.cases("multi-agent-model-scripts.json",MultiAgentScript.class).stream()
                .collect(java.util.stream.Collectors.toMap(MultiAgentScript::id,s->s));
        for(var item:fixture.cases("multi-agent-cases.json",MultiAgentCase.class)) {
            try {
                var scenario=new MultiAgentScenario(fixture,scripts.get(item.id()),cn.watsonzhu.runagent.performance.PerformanceTestSupport.properties(),null,Duration.ofSeconds(10));
                var response=scenario.execute(item);
                boolean delegation=matches(item.expectedAgents(),response.agentsUsed(),item.ordered());
                boolean isolation=scenario.isolated();
                boolean completion=scenario.successful().equals(Set.copyOf(item.expectedSuccessfulAgents()));
                boolean pass=delegation && isolation && completion;
                results.add(new CaseResult(item.id(),Category.MULTI_AGENT,Evidence.SCRIPTED_MULTI_AGENT_CONTRACT,pass?Status.PASS:Status.FAIL,
                        Map.of("multiAgentDelegationAccuracy",delegation?1.0:0,"multiAgentCapabilityIsolationAccuracy",isolation?1.0:0,
                                "multiAgentCompletionAccuracy",completion?1.0:0),pass?Reason.OK:Reason.ROUTE_MISMATCH));
            } catch(RuntimeException exception) {
                results.add(new CaseResult(item.id(),Category.MULTI_AGENT,Evidence.SCRIPTED_MULTI_AGENT_CONTRACT,Status.FAIL,
                        Map.of("multiAgentDelegationAccuracy",0.0,"multiAgentCapabilityIsolationAccuracy",0.0,"multiAgentCompletionAccuracy",0.0),Reason.EXECUTION_FAILED));
            }
        }
        return List.copyOf(results);
    }
    static boolean matches(List<String> expected,List<String> actual,boolean ordered) {
        if(actual.size()!=expected.size() || actual.stream().distinct().count()!=actual.size())return false;
        return ordered?expected.equals(actual):Set.copyOf(expected).equals(Set.copyOf(actual));
    }
}
