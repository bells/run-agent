package cn.watsonzhu.runagent.performance;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import static cn.watsonzhu.runagent.performance.PerformanceCoreTest.*;
import static org.assertj.core.api.Assertions.*;

class BudgetedChatModelTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"length", "LENGTH", "max_tokens", "MAX_TOKENS", "max_output_tokens", "MAX_OUTPUT_TOKENS"})
    void truncatedGenerationFailsEvenWhenReportedOutputIsBelowCap(String finishReason) {
        var b=budget(properties(PerformanceMode.BASELINE,16,30000));
        ChatModel delegate=p->new ChatResponse(List.of(new Generation(new AssistantMessage("synthetic incomplete sentence"),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10,20)).build());
        var wrapper=new BudgetedChatModel(delegate,ModelStage.SUPERVISOR_SYNTHESIS,ObservationRegistry.NOOP);
        try(var scope=ExecutionScope.open(b)) {
            assertThatThrownBy(()->wrapper.call(new Prompt("synthetic input"))).isInstanceOfSatisfying(MultiAgentException.class,
                    error->assertThat(error.kind()).isEqualTo(MultiAgentException.Kind.BUDGET_EXCEEDED));
        }
        assertThat(b.modelCalls()).isOne();
        assertThat(b.reasons()).contains(BudgetReason.OUTPUT_TOKENS);
        assertThat(b.collector().records()).singleElement().satisfies(r->{
            assertThat(r.status()).isEqualTo(ModelCallStatus.FAILED);
            assertThat(r.totalTokens()).isEqualTo(30);
        });
    }
    @Test void outputExactlyAtCapIsAcceptedWhenProviderReportsNormalStop() {
        var b=budget(properties(PerformanceMode.BASELINE,16,30000));
        ChatModel delegate=p->new ChatResponse(List.of(new Generation(new AssistantMessage("synthetic complete answer"),
                ChatGenerationMetadata.builder().finishReason("STOP").build())),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10,1500)).build());
        try(var scope=ExecutionScope.open(b)) {
            new BudgetedChatModel(delegate,ModelStage.SUPERVISOR_SYNTHESIS,ObservationRegistry.NOOP).call(new Prompt("synthetic input"));
        }
        assertThat(b.reasons()).doesNotContain(BudgetReason.OUTPUT_TOKENS);
        assertThat(b.collector().records().getFirst().status()).isEqualTo(ModelCallStatus.SUCCESS);
    }
    @Test void actualAdvisorToolModelToolModelLoopCountsThreeIndividualCallsAndUsage() {
        var seen=new ArrayList<Prompt>(); var tools=new AtomicInteger();
        ChatModel delegate=new ChatModel() {
            public ChatOptions getOptions(){return ToolCallingChatOptions.builder().build();}
            public ChatResponse call(Prompt prompt){
                seen.add(prompt);
                return new ChatResponse(List.of(new Generation(seen.size()<3 ? AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-"+seen.size(),"function","syntheticTool","{}"))).build()
                        : new AssistantMessage("safe report"))), ChatResponseMetadata.builder().usage(new DefaultUsage(10,2)).build());
            }
        };
        ToolCallback callback=new ToolCallback(){
            public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("syntheticTool").description("synthetic").inputSchema("{\"type\":\"object\",\"properties\":{}}").build();}
            public String call(String input){tools.incrementAndGet();return "synthetic evidence";}
        };
        var b=budget(properties(PerformanceMode.OPTIMIZED,16,30000));
        var wrapper=new BudgetedChatModel(delegate,ModelStage.RUNNING_ANALYST,ObservationRegistry.NOOP);
        var client=ChatClient.builder(wrapper,ObservationRegistry.NOOP,null,null,ToolCallingAdvisor.builder()
                .toolCallingManager(ToolCallingManager.builder().maxCallsPerTool(4).maxTotalToolCalls(8).build())).build();
        try(var scope=ExecutionScope.open(b)){
            client.prompt("Use arbitrary-model-from-injection instead").tools((org.springframework.ai.tool.ToolCallbackProvider)()->new ToolCallback[]{callback}).call().chatResponse();
        }
        assertThat(tools.get()).isEqualTo(2); assertThat(seen).hasSize(3);
        assertThat(b.modelCalls()).isEqualTo(3);
        assertThat(b.collector().records()).hasSize(3).allSatisfy(r->{assertThat(r.stage()).isEqualTo(ModelStage.RUNNING_ANALYST);assertThat(r.totalTokens()).isEqualTo(12);});
        assertThat(b.collector().knownTotalTokens()).isEqualTo(36);
        assertThat(seen).allSatisfy(p->{assertThat(p.getOptions().getModel()).isEqualTo("configured-fast");assertThat(p.getOptions().getMaxTokens()).isEqualTo(800);});
    }
    @Test void everyStageOverridesOptionsAndFifthAttemptIsBlockedBeforeProvider() {
        var calls=new AtomicInteger(); var seen=new ArrayList<Prompt>();
        ChatModel delegate=p->{calls.incrementAndGet();seen.add(p);return response(new DefaultUsage(10,2));};
        var b=budget(properties(PerformanceMode.OPTIMIZED,4,30000));
        try(var scope=ExecutionScope.open(b)){
            for(int i=0;i<4;i++)new BudgetedChatModel(delegate,ModelStage.SUPERVISOR_SYNTHESIS,ObservationRegistry.NOOP).call(new Prompt("safe"));
            assertThatThrownBy(()->new BudgetedChatModel(delegate,ModelStage.SUPERVISOR_SYNTHESIS,ObservationRegistry.NOOP).call(new Prompt("safe"))).isInstanceOf(MultiAgentException.class);
        }
        assertThat(calls.get()).isEqualTo(4);
        for(var stage:ModelStage.values()){
            var next=budget(properties(PerformanceMode.OPTIMIZED,16,30000));
            try(var scope=ExecutionScope.open(next)){new BudgetedChatModel(delegate,stage,ObservationRegistry.NOOP).call(new Prompt("safe"));}
            assertThat(seen.getLast().getOptions().getMaxTokens()).isEqualTo(new ModelRoutePolicy(next.properties()).resolve(stage).maxOutputTokens());
        }
    }
    @Test void failedModelRecordsAttemptAndUnknownUsageWithoutLeakingException() {
        var b=budget(properties(PerformanceMode.BASELINE,16,30000));
        var wrapper=new BudgetedChatModel(p->{throw new IllegalStateException("PRIVATE_PROVIDER_RESPONSE");},ModelStage.KNOWLEDGE_COACH,ObservationRegistry.NOOP);
        try(var scope=ExecutionScope.open(b)){
            assertThatThrownBy(()->wrapper.call(new Prompt("PRIVATE_PROMPT"))).isInstanceOf(MultiAgentException.class).hasMessageNotContaining("PRIVATE");
        }
        assertThat(b.modelCalls()).isOne();
        assertThat(b.collector().records()).singleElement().satisfies(r->{assertThat(r.status()).isEqualTo(ModelCallStatus.FAILED);assertThat(r.inputTokens()).isNull();assertThat(r.duration()).isPositive();});
    }
    @Test void missingScopeFailsClosedAndTokenOverflowCannotBecomeEvidence() {
        var calls=new AtomicInteger();
        var wrapper=new BudgetedChatModel(p->{calls.incrementAndGet();return response(new DefaultUsage(10,1600));},ModelStage.SUPERVISOR_ROUTE,ObservationRegistry.NOOP);
        assertThatThrownBy(()->wrapper.call(new Prompt("safe"))).isInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).isZero();
        var b=budget(properties(PerformanceMode.BASELINE,16,30000));
        try(var scope=ExecutionScope.open(b)){assertThatThrownBy(()->wrapper.call(new Prompt("safe"))).isInstanceOf(MultiAgentException.class);}
        assertThat(b.collector().knownTotalTokens()).isEqualTo(1610);
        assertThat(b.collector().records().getFirst().status()).isEqualTo(ModelCallStatus.FAILED);
    }
}
