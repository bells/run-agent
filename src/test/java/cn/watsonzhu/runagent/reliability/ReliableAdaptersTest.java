package cn.watsonzhu.runagent.reliability;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import cn.watsonzhu.runagent.config.PerformanceProperties;
import cn.watsonzhu.runagent.multiagent.MultiAgentExecutionControl;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.performance.*;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import static org.assertj.core.api.Assertions.*;
import static cn.watsonzhu.runagent.reliability.ReliabilityCoreTest.*;

class ReliableAdaptersTest {
    static ExecutionBudget budget(int limit) {
        var config=new PerformanceProperties(PerformanceMode.BASELINE,limit,1500,30000,
                Map.of(ModelTier.DEFAULT,new PerformanceProperties.ModelConfig("synthetic")),Map.of(),null,null,Map.of());
        return new ExecutionBudget("synthetic",new MultiAgentExecutionControl(Duration.ofSeconds(10)),config);
    }
    static ChatResponse response(){return new ChatResponse(List.of(),ChatResponseMetadata.builder().usage(new DefaultUsage(10,2)).build());}
    @Test void rateLimitedRetryCountsTwoActualCallsAndPreservesUnknownFailedUsage() {
        var b=budget(4);var calls=new AtomicInteger();
        var wrapper=new ReliableChatModel(p->{if(calls.incrementAndGet()==1)throw http(429);return response();},ModelStage.KNOWLEDGE_COACH,ObservationRegistry.NOOP,executor(true));
        try(var ignored=ExecutionScope.open(b)){assertThat(wrapper.call(new Prompt("synthetic"))).isNotNull();}
        assertThat(b.modelCalls()).isEqualTo(2);assertThat(calls.get()).isEqualTo(2);
        assertThat(b.collector().records()).hasSize(2);
        assertThat(b.collector().records().getFirst().usageStatus()).isEqualTo(UsageStatus.UNKNOWN);
        assertThat(b.collector().usageComplete()).isFalse();
        assertThat(new CostEstimator(b.properties().pricing()).estimate(b.collector().records()).status()).isEqualTo(CostEstimate.Status.UNKNOWN);
    }
    @Test void retryCannotConsumeSynthesisReservationAndPreflightDoesNotReserve() {
        var b=budget(2);var calls=new AtomicInteger();
        var wrapper=new ReliableChatModel(p->{calls.incrementAndGet();throw http(429);},ModelStage.KNOWLEDGE_COACH,ObservationRegistry.NOOP,executor(true));
        try(var ignored=ExecutionScope.open(b)){
            assertThatThrownBy(()->wrapper.call(new Prompt("synthetic"))).isInstanceOfSatisfying(MultiAgentException.class,e->assertThat(e.kind()).isEqualTo(MultiAgentException.Kind.BUDGET_EXCEEDED));
            assertThat(b.modelCalls()).isOne();
            new ReliableChatModel(p->response(),ModelStage.SUPERVISOR_SYNTHESIS,ObservationRegistry.NOOP,executor(true)).call(new Prompt("synthetic"));
        }
        assertThat(calls.get()).isOne();assertThat(b.modelCalls()).isEqualTo(2);
    }
    @Test void openCircuitAndFullBulkheadNeverConsumeModelBudget() {
        var e=executor(true);e.circuitBreaker(Dependency.LLM_GATEWAY).transitionToOpenState();var b=budget(4);
        try(var ignored=ExecutionScope.open(b)) {
            assertThatThrownBy(()->new ReliableChatModel(p->response(),ModelStage.KNOWLEDGE_COACH,ObservationRegistry.NOOP,e).call(new Prompt("synthetic")))
                    .isInstanceOfSatisfying(ReliabilityFailure.class,f->assertThat(f.category()).isEqualTo(FailureCategory.CIRCUIT_OPEN));
        }
        assertThat(b.modelCalls()).isZero();assertThat(b.collector().records()).isEmpty();
    }
    @Test void healthyAndDisabledModelsHaveExactlyOneCallAndDisabledErrorsKeepOldMapping() {
        for(boolean enabled:List.of(true,false)) {
            var b=budget(4);var calls=new AtomicInteger();
            try(var ignored=ExecutionScope.open(b)){
                new ReliableChatModel(p->{calls.incrementAndGet();return response();},ModelStage.RUNNING_ANALYST,ObservationRegistry.NOOP,executor(enabled)).call(new Prompt("synthetic"));
            }
            assertThat(calls.get()).isOne();assertThat(b.modelCalls()).isOne();
        }
        var b=budget(4);var calls=new AtomicInteger();
        try(var ignored=ExecutionScope.open(b)){
            assertThatThrownBy(()->new ReliableChatModel(p->{calls.incrementAndGet();throw http(429);},ModelStage.RUNNING_ANALYST,ObservationRegistry.NOOP,executor(false)).call(new Prompt("synthetic")))
                    .isInstanceOf(MultiAgentException.class);
        }
        assertThat(calls.get()).isOne();
    }
    @Test void toolIdentitySchemaAndContextArePreservedAndUnknownToolIsOnce() {
        var calls=new AtomicInteger();var definition=ToolDefinition.builder().name("accepted_alias").description("synthetic").inputSchema("{\"type\":\"object\"}").build();
        ToolCallback original=new ToolCallback(){public ToolDefinition getToolDefinition(){return definition;}public String call(String input){if(calls.incrementAndGet()%2==1)throw http(503);return "ok";}};
        var wrapper=new ReliableToolCallback(original,Dependency.RUNNING_MCP,"getRunningSummary",executor(true),ACTIVE);
        assertThat(wrapper.getToolDefinition()).isSameAs(definition);assertThat(wrapper.getToolMetadata()).isEqualTo(original.getToolMetadata());
        assertThat(wrapper.call("{}")).isEqualTo("ok");assertThat(calls.get()).isEqualTo(2);
        calls.set(0);
        assertThatThrownBy(()->new ReliableToolCallback(original,Dependency.RUNNING_MCP,"futureWriteTool",executor(true),ACTIVE).call("{}"))
                .isInstanceOf(ReliabilityFailure.class);
        assertThat(calls.get()).isOne();
    }
}
