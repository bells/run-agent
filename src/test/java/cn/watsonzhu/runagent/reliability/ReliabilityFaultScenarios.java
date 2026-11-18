package cn.watsonzhu.runagent.reliability;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.performance.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.prompt.Prompt;
import static cn.watsonzhu.runagent.reliability.ReliabilityCoreTest.*;

/** Offline entry point: only synthetic suppliers and local semaphores, never external dependencies. */
public final class ReliabilityFaultScenarios {
    private ReliabilityFaultScenarios() { }
    public record ScenarioResult(String caseId,String expectedAction,String actualAction,int attemptCount,boolean passed,String reasonCode) { }
    public static List<ScenarioResult> run() {
        var results=new ArrayList<ScenarioResult>();
        results.add(httpScenario("llm-429",Dependency.LLM_GATEWAY,429,2));
        results.add(httpScenario("llm-400",Dependency.LLM_GATEWAY,400,1));
        results.add(httpScenario("llm-401",Dependency.LLM_GATEWAY,401,1));
        results.add(httpScenario("llm-403",Dependency.LLM_GATEWAY,403,1));
        results.add(httpScenario("running-503",Dependency.RUNNING_MCP,503,2));
        results.add(circuitScenario());results.add(bulkheadScenario());results.add(deadlineScenario());
        results.add(budgetScenario());results.add(healthyScenario());
        return List.copyOf(results);
    }
    private static ScenarioResult result(String id,String expected,String actual,int attempts,boolean checks) {
        boolean passed=checks && expected.equals(actual);
        return new ScenarioResult(id,expected,actual,attempts,passed,passed?"EXPECTED_ACTION":"ACTION_MISMATCH");
    }
    private static ScenarioResult httpScenario(String id,Dependency dependency,int status,int expectedCalls) {
        var calls=new AtomicInteger();var executor=executor(true);String action;
        try {
            executor.execute(dependency,OperationSafety.READ_ONLY,ACTIVE,()->{if(calls.incrementAndGet()==1)throw http(status);return "synthetic";});
            action="RETRIED_SUCCESS";
        }catch(ReliabilityFailure failure){action=failure.category()==FailureCategory.PERMANENT?"FAIL_NO_RETRY":"UNEXPECTED_FAILURE";}
        String expected=expectedCalls==2?"RETRIED_SUCCESS":"FAIL_NO_RETRY";
        return result(id,expected,action,calls.get(),calls.get()==expectedCalls);
    }
    private static ScenarioResult circuitScenario() {
        var e=new ReliabilityExecutor(properties(true,2,2),new SimpleMeterRegistry(),()->0.5,d->{});var calls=new AtomicInteger();
        try{e.execute(Dependency.LLM_GATEWAY,OperationSafety.READ_ONLY,ACTIVE,()->{calls.incrementAndGet();throw http(503);});}catch(ReliabilityFailure ignored){}
        int before=calls.get();String action="UNEXPECTED_SUCCESS";
        try{e.execute(Dependency.LLM_GATEWAY,OperationSafety.READ_ONLY,ACTIVE,()->{calls.incrementAndGet();return "synthetic";});}
        catch(ReliabilityFailure failure){action=failure.category().name();}
        return result("shared-circuit","CIRCUIT_OPEN",action,calls.get(),before==2 && calls.get()==2);
    }
    private static ScenarioResult deadlineScenario() {
        var calls=new AtomicInteger();var sleeps=new AtomicInteger();String action="UNEXPECTED_SUCCESS";
        var e=new ReliabilityExecutor(properties(true,5,2),new SimpleMeterRegistry(),()->0.5,d->sleeps.incrementAndGet());
        var deadline=new ReliabilityDeadline(){public void checkActive(){}public Duration remaining(){return Duration.ofMillis(21);}};
        try{e.execute(Dependency.WEATHER_MCP,OperationSafety.READ_ONLY,deadline,()->{calls.incrementAndGet();throw http(503);});}
        catch(ReliabilityFailure failure){action=failure.category().name();}
        return result("deadline-before-retry","TIMEOUT",action,calls.get(),calls.get()==1 && sleeps.get()==0);
    }
    private static ScenarioResult budgetScenario() {
        var b=ReliableAdaptersTest.budget(2);var calls=new AtomicInteger();String action="UNEXPECTED_SUCCESS";
        var model=new ReliableChatModel(p->{calls.incrementAndGet();throw http(429);},ModelStage.KNOWLEDGE_COACH,ObservationRegistry.NOOP,executor(true));
        try(var scope=ExecutionScope.open(b)) {
            try{model.call(new Prompt("synthetic"));}catch(MultiAgentException failure){action=failure.kind().name();}
            new ReliableChatModel(p->ReliableAdaptersTest.response(),ModelStage.SUPERVISOR_SYNTHESIS,ObservationRegistry.NOOP,executor(true)).call(new Prompt("synthetic"));
        }
        return result("budget-reserves-synthesis","BUDGET_EXCEEDED",action,calls.get(),calls.get()==1 && b.modelCalls()==2 && b.collector().records().getFirst().usageStatus()==UsageStatus.UNKNOWN);
    }
    private static ScenarioResult healthyScenario() {
        var calls=new AtomicInteger();
        String value=executor(true).execute(Dependency.RUNNING_MCP,OperationSafety.READ_ONLY,ACTIVE,()->{calls.incrementAndGet();return "synthetic";});
        return result("healthy-single-attempt","CONTINUE","synthetic".equals(value)?"CONTINUE":"WRONG_RESULT",calls.get(),calls.get()==1);
    }
    private static ScenarioResult bulkheadScenario() {
        var e=executor(true);var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        String action="UNEXPECTED_SUCCESS";
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<String> operation=()->e.execute(Dependency.LLM_GATEWAY,OperationSafety.READ_ONLY,ACTIVE,()->{
                calls.incrementAndGet();entered.countDown();
                try{if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("Synthetic latch timeout");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Synthetic interrupted");}
                return "synthetic";
            });
            var first=pool.submit(operation);var second=pool.submit(operation);
            try {
                if(!entered.await(2,TimeUnit.SECONDS))return result("bulkhead-third-caller","BULKHEAD_REJECTED","LATCH_TIMEOUT",calls.get(),false);
                try{e.execute(Dependency.LLM_GATEWAY,OperationSafety.READ_ONLY,ACTIVE,()->{calls.incrementAndGet();return "bad";});}
                catch(ReliabilityFailure failure){action=failure.category().name();}
            }finally{release.countDown();}
            first.get(2,TimeUnit.SECONDS);second.get(2,TimeUnit.SECONDS);
        }catch(Exception failure){action="SYNTHETIC_HARNESS_FAILURE";}
        return result("bulkhead-third-caller","BULKHEAD_REJECTED",action,calls.get(),calls.get()==2);
    }
}
