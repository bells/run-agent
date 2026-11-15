package cn.watsonzhu.runagent.performance;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import cn.watsonzhu.runagent.config.PerformanceProperties;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.ai.chat.prompt.Prompt;
import static cn.watsonzhu.runagent.performance.PerformanceCoreTest.*;
import static org.assertj.core.api.Assertions.*;

class StageReasoningPolicyTest {
    @Test void liveBenchmarkUsesExplicitReasoningEnvironmentOnlyForOptimizedWhileOfflineIgnoresIt() {
        var env=Map.of("AI_GATEWAY_MODEL","synthetic-model","PERFORMANCE_REASONING_EFFORT_KNOWLEDGE_COACH","NONE");
        var settings=BenchmarkSettings.from(env,true);
        assertThat(new ModelRoutePolicy(settings.properties(PerformanceMode.OPTIMIZED)).resolve(ModelStage.KNOWLEDGE_COACH).reasoningEffort())
                .isEqualTo(ModelReasoningEffort.NONE);
        assertThat(new ModelRoutePolicy(settings.properties(PerformanceMode.BASELINE)).resolve(ModelStage.KNOWLEDGE_COACH).reasoningEffort())
                .isEqualTo(ModelReasoningEffort.PROVIDER_DEFAULT);
        assertThat(BenchmarkSettings.from(env,false).properties(PerformanceMode.OPTIMIZED).stageReasoningEffort()).isEmpty();
        assertThatThrownBy(()->BenchmarkSettings.from(Map.of("PERFORMANCE_REASONING_EFFORT_KNOWLEDGE_COACH","PRIVATE_INVALID_VALUE"),true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid benchmark reasoning policy");
    }
    static PerformanceProperties configured(PerformanceMode mode) {
        var p=properties(mode,16,30000);
        return new PerformanceProperties(p.mode(),p.maxModelCalls(),p.maxOutputTokensPerCall(),p.softTotalTokens(),
                p.models(),p.stageOutputLimits(),p.context(),p.pricing(),Map.of(ModelStage.KNOWLEDGE_COACH,ModelReasoningEffort.NONE));
    }
    @Test void onlyOptimizedConfiguredStageOverridesReasoningAndBaselineIgnoresIt() {
        for(var mode:PerformanceMode.values()) {
            var policy=new ModelRoutePolicy(configured(mode));
            for(var stage:ModelStage.values())
                assertThat(policy.resolve(stage).reasoningEffort()).isEqualTo(mode==PerformanceMode.OPTIMIZED && stage==ModelStage.KNOWLEDGE_COACH
                        ?ModelReasoningEffort.NONE:ModelReasoningEffort.PROVIDER_DEFAULT);
        }
    }
    @Test void configurationBindingUsesStageEnumsAndSafeDefaults() {
        var source=new MapConfigurationPropertySource(Map.of("run-agent.performance.mode","OPTIMIZED",
                "run-agent.performance.models.default.name","synthetic-model",
                "run-agent.performance.stage-reasoning-effort.knowledge-coach","NONE"));
        var p=new Binder(source).bind("run-agent.performance",Bindable.of(PerformanceProperties.class)).get();
        assertThat(p.stageReasoningEffort()).containsEntry(ModelStage.KNOWLEDGE_COACH,ModelReasoningEffort.NONE);
        assertThat(p.maxModelCalls()).isEqualTo(16);
        assertThat(p.context().knowledgeEvidenceChars()).isEqualTo(9000);
        var invalid=new MapConfigurationPropertySource(Map.of("run-agent.performance.stage-reasoning-effort.knowledge-coach","ARBITRARY_MODE"));
        assertThatThrownBy(()->new Binder(invalid).bind("run-agent.performance",Bindable.of(PerformanceProperties.class))).isInstanceOf(BindException.class);
    }
    @Test void unsupportedOptionsFailClosedRatherThanSilentlyIgnoringRequestedPolicy() {
        var calls=new AtomicInteger();
        org.springframework.ai.chat.model.ChatModel model=p->{calls.incrementAndGet();return response(new org.springframework.ai.chat.metadata.DefaultUsage(10,2));};
        var b=budget(configured(PerformanceMode.OPTIMIZED));
        try(var scope=ExecutionScope.open(b)) {
            assertThatThrownBy(()->new BudgetedChatModel(model,ModelStage.KNOWLEDGE_COACH,ObservationRegistry.NOOP).call(new Prompt("synthetic input")))
                    .isInstanceOf(MultiAgentException.class);
        }
        assertThat(calls.get()).isZero();
    }
}
