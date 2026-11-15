package cn.watsonzhu.runagent.performance;

import java.time.Duration;
import java.util.List;
import com.openai.models.completions.CompletionUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import static cn.watsonzhu.runagent.performance.PerformanceCoreTest.*;
import static org.assertj.core.api.Assertions.*;

class ModelUsageDiagnosticsTest {
    @Test void reasoningIsAValidatedOutputSubsetAndNeverAddedToUsageOrCostAgain() {
        var nativeUsage=CompletionUsage.builder().promptTokens(10).completionTokens(20).totalTokens(30)
                .completionTokensDetails(CompletionUsage.CompletionTokensDetails.builder().reasoningTokens(12).build()).build();
        var b=budget(properties(PerformanceMode.BASELINE,16,30000));
        var response=new ChatResponse(List.of(new Generation(new AssistantMessage("私😀"),
                ChatGenerationMetadata.builder().finishReason("STOP").build())),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10,20,30,nativeUsage)).build());
        var r=b.collector().record(ModelStage.SUPERVISOR_SYNTHESIS,new ModelRoutePolicy(b.properties()).resolve(ModelStage.SUPERVISOR_SYNTHESIS),
                response,Duration.ofMillis(1),ModelCallStatus.SUCCESS);
        assertThat(r.reasoningTokens()).isEqualTo(12);
        assertThat(r.outputCharacters()).isEqualTo(2);
        assertThat(r.finishReason()).isEqualTo(ModelUsageRecord.FinishReason.STOP);
        assertThat(r.totalTokens()).isEqualTo(30);
        assertThat(new CostEstimator(b.properties().pricing()).estimate(List.of(r)).amount()).isEqualByComparingTo("0.000100");
        assertThat(r.toString()).doesNotContain("私😀","nativeUsage");
    }
    @ParameterizedTest @ValueSource(longs={-1,21})
    void invalidReasoningCountsAreUnknownWithoutDiscardingKnownTotal(long reasoning) {
        var nativeUsage=CompletionUsage.builder().promptTokens(10).completionTokens(20).totalTokens(30)
                .completionTokensDetails(CompletionUsage.CompletionTokensDetails.builder().reasoningTokens(reasoning).build()).build();
        var b=budget(properties(PerformanceMode.BASELINE,16,30000));
        var r=record(b,ModelStage.KNOWLEDGE_COACH,new DefaultUsage(10,20,30,nativeUsage));
        assertThat(r.reasoningTokens()).isNull();
        assertThat(r.usageStatus()).isEqualTo(UsageStatus.KNOWN);
        assertThat(r.totalTokens()).isEqualTo(30);
    }
    @Test void missingDiagnosticsRemainUnknownAndArbitraryFinishReasonCannotEnterSafeRecord() {
        var b=budget(properties(PerformanceMode.BASELINE,16,30000));
        var failed=b.collector().record(ModelStage.KNOWLEDGE_COACH,new ModelRoutePolicy(b.properties()).resolve(ModelStage.KNOWLEDGE_COACH),
                null,Duration.ofMillis(1),ModelCallStatus.FAILED);
        assertThat(failed.reasoningTokens()).isNull();assertThat(failed.outputCharacters()).isNull();
        var response=new ChatResponse(List.of(new Generation(new AssistantMessage("PRIVATE_COMPLETION"),
                ChatGenerationMetadata.builder().finishReason("PRIVATE_FINISH_REASON").build())));
        var unknown=b.collector().record(ModelStage.KNOWLEDGE_COACH,new ModelRoutePolicy(b.properties()).resolve(ModelStage.KNOWLEDGE_COACH),
                response,Duration.ofMillis(1),ModelCallStatus.SUCCESS);
        assertThat(unknown.finishReason()).isEqualTo(ModelUsageRecord.FinishReason.UNKNOWN);
        assertThat(unknown.reasoningTokens()).isNull();
        assertThat(unknown.toString()).doesNotContain("PRIVATE");
    }
    @Test void explicitZeroReasoningIsKnownZero() {
        var nativeUsage=CompletionUsage.builder().promptTokens(10).completionTokens(20).totalTokens(30)
                .completionTokensDetails(CompletionUsage.CompletionTokensDetails.builder().reasoningTokens(0).build()).build();
        var b=budget(properties(PerformanceMode.BASELINE,16,30000));
        assertThat(record(b,ModelStage.KNOWLEDGE_COACH,new DefaultUsage(10,20,30,nativeUsage)).reasoningTokens()).isZero();
    }
}
