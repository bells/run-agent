package cn.watsonzhu.runagent.reliability;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.performance.*;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import static cn.watsonzhu.runagent.reliability.ReliabilityCoreTest.*;
import static org.assertj.core.api.Assertions.*;

class ReliabilityAttemptBoundaryTest {
    @Test void rejectedModelBulkheadDoesNotReserveOrCollect() throws Exception {
        var e=executor(true);var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<String> hold=()->e.execute(Dependency.LLM_GATEWAY,OperationSafety.READ_ONLY,ACTIVE,()->{
                entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}return "ok";
            });
            var first=pool.submit(hold);var second=pool.submit(hold);
            try {
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                var b=ReliableAdaptersTest.budget(4);
                try(var scope=ExecutionScope.open(b)) {
                    assertThatThrownBy(()->new ReliableChatModel(p->ReliableAdaptersTest.response(),ModelStage.KNOWLEDGE_COACH,ObservationRegistry.NOOP,e).call(new Prompt("synthetic")))
                            .isInstanceOfSatisfying(ReliabilityFailure.class,f->assertThat(f.category()).isEqualTo(FailureCategory.BULKHEAD_REJECTED));
                }
                assertThat(b.modelCalls()).isZero();assertThat(b.collector().records()).isEmpty();
            }finally{release.countDown();}
            assertThat(first.get(2,TimeUnit.SECONDS)).isEqualTo("ok");assertThat(second.get(2,TimeUnit.SECONDS)).isEqualTo("ok");
        }
    }
    @Test void halfOpenFailedProbeReopensCircuitAndPreventsSecondProviderCall() {
        var e=executor(true);e.circuitBreaker(Dependency.WEATHER_MCP).transitionToOpenState();
        e.circuitBreaker(Dependency.WEATHER_MCP).transitionToHalfOpenState();
        var calls=new AtomicInteger();
        for(int i=0;i<2;i++)assertThatThrownBy(()->e.execute(Dependency.WEATHER_MCP,OperationSafety.NON_IDEMPOTENT,ACTIVE,()->{calls.incrementAndGet();throw http(503);})).isInstanceOf(ReliabilityFailure.class);
        assertThat(e.circuitBreaker(Dependency.WEATHER_MCP).getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(()->e.execute(Dependency.WEATHER_MCP,OperationSafety.READ_ONLY,ACTIVE,()->{calls.incrementAndGet();return "bad";})).isInstanceOf(ReliabilityFailure.class);
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void eachRetryRechecksBudgetAfterSleepBeforeCircuitAndProvider() {
        var b=ReliableAdaptersTest.budget(3);var calls=new AtomicInteger();
        var e=new ReliabilityExecutor(properties(true,5,2),new SimpleMeterRegistry(),()->0.5,d->b.reserve(ModelStage.RUNNING_ANALYST));
        try(var scope=ExecutionScope.open(b)) {
            assertThatThrownBy(()->new ReliableChatModel(p->{calls.incrementAndGet();throw http(429);},ModelStage.RUNNING_ANALYST,ObservationRegistry.NOOP,e).call(new Prompt("synthetic")))
                    .isInstanceOfSatisfying(MultiAgentException.class,f->assertThat(f.kind()).isEqualTo(MultiAgentException.Kind.BUDGET_EXCEEDED));
        }
        assertThat(calls.get()).isOne();
        assertThat(e.circuitBreaker(Dependency.LLM_GATEWAY).getMetrics().getNumberOfFailedCalls()).isOne();
    }
    @Test void actualToolContextAndInputSurviveRetryWithoutMutation() {
        var input=new AtomicReference<String>();var context=new AtomicReference<org.springframework.ai.chat.model.ToolContext>();var calls=new AtomicInteger();
        var expectedContext=new org.springframework.ai.chat.model.ToolContext(java.util.Map.of("fixed","marker"));
        ToolCallback callback=new ToolCallback(){
            public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("safe").description("synthetic").inputSchema("{}").build();}
            public String call(String value){throw new AssertionError("Context path required");}
            public String call(String value,org.springframework.ai.chat.model.ToolContext ctx){input.set(value);context.set(ctx);if(calls.incrementAndGet()==1)throw http(503);return "ok";}
        };
        assertThat(new ReliableToolCallback(callback,Dependency.WEATHER_MCP,"openmeteo_get_forecast",executor(true),ACTIVE).call("{}",expectedContext)).isEqualTo("ok");
        assertThat(context.get()).isSameAs(expectedContext);assertThat(input.get()).isEqualTo("{}");assertThat(calls.get()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"getRunningSummary","getRecentRuns","getPersonalBest","openmeteo_search_locations","openmeteo_get_forecast","openmeteo_get_air_quality"})
    void readAllowlistNeverTransfersAcrossDependencies(String name) {
        var calls=new AtomicInteger();var wrong=name.startsWith("openmeteo")?Dependency.RUNNING_MCP:Dependency.WEATHER_MCP;
        ToolCallback callback=new ToolCallback(){public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name(name).description("synthetic").inputSchema("{}").build();}public String call(String input){calls.incrementAndGet();throw http(503);}};
        assertThatThrownBy(()->new ReliableToolCallback(callback,wrong,name,executor(true),ACTIVE).call("{}" )).isInstanceOf(ReliabilityFailure.class);
        assertThat(calls.get()).isOne();
    }
    @Test void unknownNullToolNameFailsClosedWithoutRetry() {
        var calls=new AtomicInteger();ToolCallback callback=new ToolCallback(){public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("future").description("synthetic").inputSchema("{}").build();}public String call(String input){calls.incrementAndGet();throw http(503);}};
        assertThatThrownBy(()->new ReliableToolCallback(callback,Dependency.RUNNING_MCP,null,executor(true),ACTIVE).call("{}")).isInstanceOf(ReliabilityFailure.class);
        assertThat(calls.get()).isOne();
    }
}
