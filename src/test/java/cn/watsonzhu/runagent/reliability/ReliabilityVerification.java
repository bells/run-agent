package cn.watsonzhu.runagent.reliability;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import cn.watsonzhu.runagent.workflow.WorkflowRecoveryScenarios;
import tools.jackson.databind.json.JsonMapper;

/** Independent offline runner. Reports contain observations of assertions, never exception details. */
public final class ReliabilityVerification {
    public record Case(String caseId,String category,String expectedAction,String actualAction,Integer attemptCount,
                       String attemptUnit,boolean passed,String reasonCode) { }
    public record Report(String applicationVersion,String verificationType,int scenarioCount,int passed,int failed,
            int retryScenarios,int circuitScenarios,int bulkheadScenarios,int degradationScenarios,int recoveryScenarios,
            boolean budgetSafety,boolean privacySafety,List<Case> scenarios,List<ReliabilityGatewayScenarios.Result> gatewaySamples,
            List<ReliabilityQualityScenarios.Result> qualitySamples) { }
    private static final JsonMapper JSON=new JsonMapper();
    public static void main(String[] args) throws Exception {
        var directory=Path.of(args[0]);Files.createDirectories(directory);
        List<Case> scenarios=new ArrayList<>();
        for(var result:ReliabilityFaultScenarios.run()) {
            String category=switch(result.caseId()) {
                case "shared-circuit" -> "CIRCUIT";
                case "bulkhead-third-caller" -> "BULKHEAD";
                case "budget-reserves-synthesis","deadline-before-retry" -> "BUDGET";
                default -> "RETRY";
            };
            scenarios.add(new Case(result.caseId(),category,result.expectedAction(),result.actualAction(),result.attemptCount(),"DEPENDENCY",result.passed(),result.reasonCode()));
        }
        for(var result:ReliabilityIntegrationScenarios.run())scenarios.add(new Case(result.caseId(),"DEGRADATION",result.expectedAction(),result.actualAction(),result.attemptCount(),"GRAPH_DELEGATION",result.passed(),result.reasonCode()));
        try {
            for(var result:ReliabilitySpecialistFaultScenarios.run())scenarios.add(new Case(result.caseId(),"DEGRADATION",
                    "DEPENDENCY_FAILURE_PARTIAL_SUCCESS","DEPENDENCY_FAILURE_PARTIAL_SUCCESS",result.dependencyAttempts(),"DEPENDENCY",result.passed(),"REAL_SPECIALIST_GRAPH"));
        }catch(Exception | AssertionError failure) {scenarios.add(failed("specialist-fault-paths","DEGRADATION"));}
        List<ReliabilityGatewayScenarios.Result> gateway=new ArrayList<>();
        for(int status:new int[]{429,503,400,401,403,200})runGateway(scenarios,gateway,status,true);
        runGateway(scenarios,gateway,429,false);
        List<ReliabilityQualityScenarios.Result> quality=List.of();
        try {
            quality=ReliabilityQualityScenarios.run();
            for(var result:quality)scenarios.add(new Case("quality-"+result.caseId(),"QUALITY","SAME_CALLS_AND_QUALITY","SAME_CALLS_AND_QUALITY",result.enabledModelCalls(),"MODEL",result.passed(),"SCRIPTED_CONTRACT"));
        }catch(Exception | AssertionError failure) { scenarios.add(failed("same-seven-quality-cases","QUALITY")); }
        try {
            int completedNodeCalls=WorkflowRecoveryScenarios.verifyResume();
            scenarios.add(new Case("checkpoint-resume","RECOVERY","RESUME_FAILED_NODE","RESUME_FAILED_NODE",completedNodeCalls,"COMPLETED_RUNNING_NODE",completedNodeCalls==1,"REAL_MEMORY_SAVER"));
            scenarios.add(new Case("checkpoint-completed-node-once","RECOVERY","DO_NOT_REPEAT_COMPLETED","DO_NOT_REPEAT_COMPLETED",completedNodeCalls,"COMPLETED_RUNNING_NODE",completedNodeCalls==1,"REAL_MEMORY_SAVER"));
        }catch(Exception | AssertionError failure) { scenarios.add(failed("checkpoint-resume","RECOVERY")); }
        int passed=(int)scenarios.stream().filter(Case::passed).count();
        boolean budget=scenarios.stream().filter(item->Set.of("BUDGET","QUALITY").contains(item.category())).allMatch(Case::passed)
                && gateway.stream().allMatch(item->item.httpRequests()==item.modelCalls());
        boolean privacy=privacySafe(scenarios,gateway,quality);
        var report=new Report(args[1],"DETERMINISTIC_SYNTHETIC_CONTRACT",scenarios.size(),passed,scenarios.size()-passed,
                count(scenarios,"RETRY"),count(scenarios,"CIRCUIT"),count(scenarios,"BULKHEAD"),count(scenarios,"DEGRADATION"),count(scenarios,"RECOVERY"),budget,privacy,List.copyOf(scenarios),List.copyOf(gateway),quality);
        Files.writeString(directory.resolve("report.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        StringBuilder md=new StringBuilder("# RunAgent reliability verification\n\nType: DETERMINISTIC_SYNTHETIC_CONTRACT\n\nVersion: ").append(args[1])
                .append("\n\nScenarios: ").append(report.scenarioCount()).append("; passed: ").append(passed).append("; failed: ").append(report.failed())
                .append("\n\nBudget safety: ").append(budget).append("; privacy safety: ").append(privacy)
                .append("\n\n| Case | Expected | Actual | Attempts | Unit | Result |\n| --- | --- | --- | ---: | --- | --- |\n");
        for(var item:scenarios)md.append('|').append(item.caseId()).append('|').append(item.expectedAction()).append('|').append(item.actualAction()).append('|').append(item.attemptCount()).append('|').append(item.attemptUnit()).append('|').append(item.passed()?"PASS":"FAIL").append("|\n");
        md.append("\nCounts refer to each row's unit, not interchangeable model/tool counts. Gateway samples in JSON include actual HTTP requests, model calls, retry count, latency and usage/cost completeness. Failed provider attempts have UNKNOWN usage/cost. Healthy-path known synthetic usage/prices are test fixtures. Quality uses the same seven committed multi-agent cases. Recovery is JVM-lifetime official MemorySaver. These contracts do not measure live answer quality or production latency.\n");
        Files.writeString(directory.resolve("report.md"),md);
        System.out.println("Reliability verification: scenarios="+report.scenarioCount()+" passed="+passed+" failed="+report.failed()+" budgetSafety="+budget+" privacySafety="+privacy);
        if(report.failed()>0 || !budget || !privacy)throw new IllegalStateException("Reliability verification quality gate failed");
    }
    private static void runGateway(List<Case> cases,List<ReliabilityGatewayScenarios.Result> samples,int status,boolean enabled) {
        String id="gateway-"+status+(enabled?"-enabled":"-disabled");
        try {
            var result=ReliabilityGatewayScenarios.verify(status,enabled);samples.add(result);
            String action=result.retries()==1?"RETRY_ONCE":status==200?"HEALTHY_ONE_CALL":"NO_RETRY";
            cases.add(new Case(id,"RETRY",action,action,result.httpRequests(),"HTTP",true,"SDK_RETRIES_ZERO"));
        }catch(Exception | AssertionError failure) { cases.add(failed(id,"RETRY")); }
    }
    private static Case failed(String id,String category) {return new Case(id,category,"CONTRACT_VERIFIED","FAILED",null,"UNAVAILABLE",false,"CONTRACT_FAILED");}
    private static int count(List<Case> cases,String category) {return (int)cases.stream().filter(item->item.category().equals(category)).count();}
    private static boolean privacySafe(List<Case> cases,List<ReliabilityGatewayScenarios.Result> gateway,List<ReliabilityQualityScenarios.Result> quality) {
        String text=JSON.writeValueAsString(List.of(cases,gateway,quality));
        return List.of("PRIVATE_PROVIDER_BODY_SENTINEL","synthetic-key","synthetic input","synthetic partial advice","synthetic city")
                .stream().noneMatch(text::contains);
    }
}
