package cn.watsonzhu.runagent.performance;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class PerformanceBenchmarkTest {
    @TempDir Path output;
    @Test void offlineBenchmarkExecutesSameDatasetBothModesAndWritesOnlySafeMetrics() throws Exception {
        var report=PerformanceBenchmark.run(output,"0.10.0-SNAPSHOT",BenchmarkSettings.offline(),null);
        assertThat(report.benchmarkType()).isEqualTo("SYNTHETIC_CONTRACT");
        assertThat(report.cases()).hasSize(14);
        assertThat(report.profiles()).hasSize(2);
        assertThat(report.profiles().values()).allSatisfy(p->{
            assertThat(p.successRate()).isEqualTo(1);assertThat(p.delegationAccuracy()).isEqualTo(1);
            assertThat(p.completionAccuracy()).isEqualTo(1);assertThat(p.toolContractAccuracy()).isEqualTo(1);
            assertThat(p.qualityGate()).isTrue();assertThat(p.usageCompleteness()).isEqualTo(1);
            assertThat(p.costCompleteness()).isEqualTo(1);
        });
        assertThat(report.datasetChecksum()).matches("[a-f0-9]{64}");
        String json=Files.readString(output.resolve("report.json"));
        String markdown=Files.readString(output.resolve("report.md"));
        assertThat(json).doesNotContain("question\"", "prompt\"", "content\"", "arguments\"", "toolResult", "Synthetic integrated advice", "个人跑步", "API_KEY");
        assertThat(markdown).contains("SYNTHETIC_CONTRACT", "BASELINE", "OPTIMIZED", "small sample", "Observed P95", "UNKNOWN");
        assertThat(new JsonMapper().readTree(json).path("cases").size()).isEqualTo(14);
    }
    @Test void failedSpecialistWithoutAnyToolAttemptMustFailLiveToolContract() throws Exception {
        try(var fixture=new cn.watsonzhu.runagent.evaluation.EvaluationFixtures()) {
            var item=fixture.cases("multi-agent-cases.json",cn.watsonzhu.runagent.evaluation.EvaluationModels.MultiAgentCase.class).stream().filter(c->c.id().equals("ma-running-failure")).findFirst().orElseThrow();
            var script=fixture.cases("multi-agent-model-scripts.json",cn.watsonzhu.runagent.evaluation.EvaluationModels.MultiAgentScript.class).stream().filter(c->c.id().equals(item.id())).findFirst().orElseThrow();
            var routing=new java.util.concurrent.atomic.AtomicInteger();
            org.springframework.ai.chat.model.ChatModel live=new org.springframework.ai.chat.model.ChatModel() {
                public org.springframework.ai.chat.prompt.ChatOptions getOptions(){return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();}
                public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt p){
                    String text="no tool evidence";
                    if(p.getSystemMessage().getText().contains("You are the RunAgent supervisor."))text="{\"nextAgent\":\""+script.decisions().get(routing.getAndIncrement())+"\",\"task\":\"轻松跑 task\"}";
                    else if(p.getSystemMessage().getText().contains("final RunAgent supervisor"))text="safe integrated answer";
                    return new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(text))));
                }
            };
            var scenario=new cn.watsonzhu.runagent.evaluation.MultiAgentScenario(fixture,script,BenchmarkSettings.offline().properties(PerformanceMode.BASELINE),live,java.time.Duration.ofSeconds(10));
            scenario.execute(item);
            assertThat(scenario.toolContract()).isFalse();
        }
    }
    @Test void liveConfigurationIsExplicitAndDoesNotReuseUnrelatedEvaluationFlag() {
        assertThat(BenchmarkSettings.from(Map.of("LIVE_EVALUATION_ENABLED","true"),true).enabled()).isFalse();
        var disabled=BenchmarkSettings.from(Map.of(),true);
        assertThat(disabled.enabled()).isFalse();assertThat(disabled.runs()).isEqualTo(3);assertThat(disabled.warmup()).isOne();
        assertThat(BenchmarkSettings.from(Map.of("LIVE_PERFORMANCE_BENCHMARK_ENABLED","true"),true).ready()).isFalse();
        assertThatThrownBy(()->BenchmarkSettings.from(Map.of("PERFORMANCE_BENCHMARK_RUNS","0"),true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void configuredUnpricedLiveModelsDoNotInheritSyntheticPricing() {
        var s=BenchmarkSettings.from(Map.of("LIVE_PERFORMANCE_BENCHMARK_ENABLED","true","AI_GATEWAY_MODEL","trusted-model","AI_GATEWAY_BASE_URL","https://example.invalid","AI_GATEWAY_API_KEY","private-key"),true);
        var p=s.properties(PerformanceMode.OPTIMIZED);
        assertThat(p.pricing().tiers().values()).noneMatch(cn.watsonzhu.runagent.config.PerformanceProperties.Price::known);
        assertThat(s.toString()).doesNotContain("private-key");
        assertThat(new ModelRoutePolicy(p).resolve(ModelStage.RUNNING_ANALYST).tier()).isEqualTo(ModelTier.DEFAULT);
    }
}
