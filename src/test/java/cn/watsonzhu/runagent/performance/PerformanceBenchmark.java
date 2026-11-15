package cn.watsonzhu.runagent.performance;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.ToDoubleFunction;
import cn.watsonzhu.runagent.evaluation.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import static cn.watsonzhu.runagent.evaluation.EvaluationModels.*;

/** Test-source-only runner: same dataset, sequential profiles, synthetic dependencies in both modes. */
public final class PerformanceBenchmark {
    private PerformanceBenchmark() { }
    public record CaseMeasurement(String caseId, int run, PerformanceMode mode, boolean success, boolean delegationCorrect,
            boolean completionCorrect, boolean toolContractCorrect, boolean capabilityIsolationCorrect, String reasonCode,
            double latencyMs, int modelCalls, Long inputTokens, Long outputTokens, Long totalTokens, boolean usageComplete,
            CostEstimate cost, Map<ModelStage, StageMeasurement> stageBreakdown) { }
    public record StageMeasurement(int calls, double stageMs, double modelMs, long knownTokens, int unknownCalls) { }
    public record Profile(int samples, double successRate, double delegationAccuracy, double completionAccuracy,
            double toolContractAccuracy, double capabilityIsolationAccuracy, boolean qualityGate,
            double averageLatencyMs, double p50LatencyMs, double observedP95LatencyMs, double averageModelCalls,
            Double averageInputTokens, Double averageOutputTokens, Double averageTotalTokens, double usageCompleteness,
            java.math.BigDecimal averageEstimatedCost, double costCompleteness, Map<ModelStage,StageMeasurement> stageBreakdown) { }
    public record Report(String runTimestamp,String applicationVersion,String benchmarkType,String metricDefinitionVersion,
            Map<String,DatasetIdentity> datasets,String datasetChecksum,int measuredRuns,int warmupsPerCaseAndMode,
            Map<PerformanceMode,Map<ModelStage,ModelRoute>> routes,Map<PerformanceMode,Profile> profiles,List<CaseMeasurement> cases,
            boolean qualityGate, String conclusion) { }
    public static void main(String[] args) throws Exception {
        boolean live=args.length>2 && "live".equals(args[2]);
        var settings=BenchmarkSettings.from(System.getenv(),live);
        if(!settings.enabled()){System.out.println("Live performance benchmark SKIPPED: disabled.");return;}
        if(!settings.ready()){System.out.println("Live performance benchmark SKIPPED: explicit AI_GATEWAY_* configuration required.");return;}
        // Validate safe model identities and prices before any paid call.
        settings.properties(PerformanceMode.BASELINE);settings.properties(PerformanceMode.OPTIMIZED);
        ChatModel model=live?OpenAiChatModel.builder().options(OpenAiChatOptions.builder().baseUrl(settings.baseUrl()).apiKey(settings.apiKey())
                .model(settings.defaultModel()).timeout(settings.timeout()).maxRetries(0).build()).build():null;
        var report=run(Path.of(args[0]),args[1],settings,model);
        System.out.println(report.benchmarkType()+": cases="+report.cases().size()+" qualityGate="+report.qualityGate());
        if(!report.qualityGate() && !live)throw new IllegalStateException("Synthetic performance contract quality gate failed");
    }
    public static Report run(Path output,String version,BenchmarkSettings settings,ChatModel liveModel) throws Exception {
        if(!settings.ready() || settings.live() && liveModel==null)throw new IllegalArgumentException("Benchmark disabled or unconfigured");
        var cases=new ArrayList<CaseMeasurement>();
        var routes=new EnumMap<PerformanceMode,Map<ModelStage,ModelRoute>>(PerformanceMode.class);
        Map<String,DatasetIdentity> identities;
        try(var fixture=new EvaluationFixtures()) {
            identities=fixture.identities();
            var scripts=fixture.cases("multi-agent-model-scripts.json",MultiAgentScript.class).stream().collect(java.util.stream.Collectors.toMap(MultiAgentScript::id,s->s));
            var dataset=fixture.cases("multi-agent-cases.json",MultiAgentCase.class);
            for(var mode:PerformanceMode.values()) {
                var properties=settings.properties(mode);var policy=new ModelRoutePolicy(properties);
                var mapping=new EnumMap<ModelStage,ModelRoute>(ModelStage.class);
                for(var stage:ModelStage.values())mapping.put(stage,policy.resolve(stage));
                routes.put(mode,Map.copyOf(mapping));
                for(var item:dataset) {
                    for(int run=-settings.warmup();run<settings.runs();run++) {
                        var scenario=new MultiAgentScenario(fixture,scripts.get(item.id()),properties,liveModel,settings.timeout());
                        var result=measure(item,scenario,mode,run);
                        if(run>=0)cases.add(result);
                    }
                }
            }
        }
        var profiles=new EnumMap<PerformanceMode,Profile>(PerformanceMode.class);
        for(var mode:PerformanceMode.values())profiles.put(mode,aggregate(cases.stream().filter(c->c.mode()==mode).toList()));
        boolean gate=profiles.values().stream().allMatch(Profile::qualityGate);
        String checksum=EvaluationReportWriter.checksum(new TreeMap<>(identities).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var report=new Report(Instant.now().toString(),version,settings.live()?"LIVE_MODEL":"SYNTHETIC_CONTRACT","v1",identities,checksum,settings.runs(),settings.warmup(),Map.copyOf(routes),Map.copyOf(profiles),List.copyOf(cases),gate,
                settings.live()?"Small sample; report measured quality/latency/tokens/cost together. No production P95 claim.":"Synthetic contract only; no measurable real-model improvement is established.");
        PerformanceReportWriter.write(output,report);
        return report;
    }
    private static CaseMeasurement measure(MultiAgentCase item,MultiAgentScenario scenario,PerformanceMode mode,int run) {
        boolean success=false,delegation=false,completion=false;String reason="OK";
        try {
            var response=scenario.execute(item);success=true;
            delegation=response.agentsUsed().size()==item.expectedAgents().size() && (item.ordered()?response.agentsUsed().equals(item.expectedAgents()):Set.copyOf(response.agentsUsed()).equals(Set.copyOf(item.expectedAgents())));
            completion=scenario.successful().equals(Set.copyOf(item.expectedSuccessfulAgents()));
            if(!delegation || !completion || !scenario.toolContract() || !scenario.isolated())reason="CONTRACT_MISMATCH";
        } catch(RuntimeException exception) {
            reason=exception instanceof cn.watsonzhu.runagent.exception.MultiAgentException failure?failure.kind().name():"EXECUTION_FAILED";
        }
        var snapshot=scenario.snapshot();
        var stages=new EnumMap<ModelStage,StageMeasurement>(ModelStage.class);
        snapshot.stageBreakdown().forEach((stage,summary)->stages.put(stage,new StageMeasurement(summary.modelCalls(),summary.stageDuration().toNanos()/1e6,summary.modelDuration().toNanos()/1e6,summary.knownTotalTokens(),summary.unknownCalls())));
        return new CaseMeasurement(item.id(),run,mode,success,delegation,completion,scenario.toolContract(),scenario.isolated(),reason,snapshot.totalLatency().toNanos()/1e6,
                snapshot.modelCalls(),snapshot.usageComplete()?snapshot.knownInputTokens():null,snapshot.usageComplete()?snapshot.knownOutputTokens():null,
                snapshot.usageComplete()?snapshot.knownTotalTokens():null,snapshot.usageComplete(),snapshot.estimatedCost(),Map.copyOf(stages));
    }
    static Profile aggregate(List<CaseMeasurement> cases) {
        if(cases.isEmpty())throw new IllegalArgumentException("No measured samples");
        var latencies=cases.stream().mapToDouble(CaseMeasurement::latencyMs).sorted().toArray();
        boolean usage=cases.stream().allMatch(CaseMeasurement::usageComplete);
        boolean costs=cases.stream().allMatch(c->c.cost().status()==CostEstimate.Status.KNOWN);
        java.math.BigDecimal cost=costs?cases.stream().map(c->c.cost().amount()).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add)
                .divide(java.math.BigDecimal.valueOf(cases.size()),12,java.math.RoundingMode.HALF_UP):null;
        var stages=new EnumMap<ModelStage,StageMeasurement>(ModelStage.class);
        for(var stage:ModelStage.values()) {
            var values=cases.stream().map(c->c.stageBreakdown().get(stage)).toList();
            stages.put(stage,new StageMeasurement(values.stream().mapToInt(StageMeasurement::calls).sum(),values.stream().mapToDouble(StageMeasurement::stageMs).sum(),
                    values.stream().mapToDouble(StageMeasurement::modelMs).sum(),values.stream().mapToLong(StageMeasurement::knownTokens).sum(),values.stream().mapToInt(StageMeasurement::unknownCalls).sum()));
        }
        double success=avg(cases,c->c.success()?1:0),delegation=avg(cases,c->c.delegationCorrect()?1:0),completion=avg(cases,c->c.completionCorrect()?1:0),tools=avg(cases,c->c.toolContractCorrect()?1:0),isolation=avg(cases,c->c.capabilityIsolationCorrect()?1:0);
        return new Profile(cases.size(),success,delegation,completion,tools,isolation,success==1 && delegation==1 && completion==1 && tools==1 && isolation==1,
                avg(cases,CaseMeasurement::latencyMs),percentile(latencies,0.5),percentile(latencies,0.95),avg(cases,CaseMeasurement::modelCalls),
                usage?avg(cases,c->c.inputTokens()):null,usage?avg(cases,c->c.outputTokens()):null,usage?avg(cases,c->c.totalTokens()):null,
                avg(cases,c->c.usageComplete()?1:0),cost,avg(cases,c->c.cost().status()==CostEstimate.Status.KNOWN?1:0),Map.copyOf(stages));
    }
    private static double avg(List<CaseMeasurement> cases,ToDoubleFunction<CaseMeasurement> f){return cases.stream().mapToDouble(f).average().orElseThrow();}
    static double percentile(double[] sorted,double percentile){return sorted[Math.max(0,(int)Math.ceil(sorted.length*percentile)-1)];}
}
