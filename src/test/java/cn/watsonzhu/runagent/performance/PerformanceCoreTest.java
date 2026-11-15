package cn.watsonzhu.runagent.performance;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import cn.watsonzhu.runagent.config.PerformanceProperties;
import cn.watsonzhu.runagent.multiagent.MultiAgentExecutionControl;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.document.Document;
import static org.assertj.core.api.Assertions.*;

class PerformanceCoreTest {
    static PerformanceProperties properties(PerformanceMode mode, int calls, long tokens) {
        return new PerformanceProperties(mode, calls, 1500, tokens,
                Map.of(ModelTier.DEFAULT, new PerformanceProperties.ModelConfig("configured-default"),
                        ModelTier.FAST, new PerformanceProperties.ModelConfig("configured-fast"),
                        ModelTier.STRONG, new PerformanceProperties.ModelConfig("configured-strong")),
                Map.of(), new PerformanceProperties.Context(600, 9000),
                new PerformanceProperties.Pricing("USD", Map.of(ModelTier.DEFAULT, new PerformanceProperties.Price(new BigDecimal("2"), new BigDecimal("4")))), Map.of());
    }
    static ExecutionBudget budget(PerformanceProperties properties) {
        return new ExecutionBudget("safe-execution", new MultiAgentExecutionControl(Duration.ofSeconds(10)), properties);
    }
    static ChatResponse response(org.springframework.ai.chat.metadata.Usage usage) {
        return new ChatResponse(List.of(), ChatResponseMetadata.builder().usage(usage).build());
    }
    static ModelUsageRecord record(ExecutionBudget budget, ModelStage stage, org.springframework.ai.chat.metadata.Usage usage) {
        var record = budget.collector().record(stage, new ModelRoutePolicy(budget.properties()).resolve(stage), response(usage), Duration.ofMillis(3), ModelCallStatus.SUCCESS);
        budget.reconcile();
        return record;
    }
    @Test void baselineAlwaysUsesDefaultAndAbsoluteOutputGuard() {
        var policy = new ModelRoutePolicy(properties(PerformanceMode.BASELINE,16,30000));
        for (var stage : ModelStage.values()) {
            assertThat(policy.resolve(stage).tier()).isEqualTo(ModelTier.DEFAULT);
            assertThat(policy.resolve(stage).model()).isEqualTo("configured-default");
            assertThat(policy.resolve(stage).maxOutputTokens()).isEqualTo(1500);
        }
    }
    @Test void optimizedRoutesAndCapsAreStageSpecific() {
        var policy = new ModelRoutePolicy(properties(PerformanceMode.OPTIMIZED,16,30000));
        assertThat(Arrays.stream(ModelStage.values()).map(policy::resolve).map(ModelRoute::tier)).containsExactly(
                ModelTier.FAST,ModelTier.FAST,ModelTier.DEFAULT,ModelTier.FAST,ModelTier.STRONG);
        assertThat(Arrays.stream(ModelStage.values()).map(policy::resolve).map(ModelRoute::maxOutputTokens)).containsExactly(256,800,1000,800,1200);
    }
    @Test void fallbackResolvesDefaultAndUsesDefaultPrice() {
        var p = properties(PerformanceMode.OPTIMIZED,16,30000);
        var fallback = new PerformanceProperties(p.mode(),16,1500,30000,Map.of(ModelTier.DEFAULT,p.models().get(ModelTier.DEFAULT)),Map.of(),p.context(),p.pricing(),Map.of());
        var route = new ModelRoutePolicy(fallback).resolve(ModelStage.RUNNING_ANALYST);
        assertThat(route.requestedTier()).isEqualTo(ModelTier.FAST);
        assertThat(route.tier()).isEqualTo(ModelTier.DEFAULT);
        assertThat(route.model()).isEqualTo("configured-default");
        var b = budget(fallback);
        var r = record(b,ModelStage.RUNNING_ANALYST,new DefaultUsage(1000000,500000));
        assertThat(new CostEstimator(fallback.pricing()).estimate(List.of(r)).amount()).isEqualByComparingTo("4");
    }
    @Test void finalSlotIsReservedAndFifthAttemptNeverReserved() {
        var b=budget(properties(PerformanceMode.BASELINE,4,30000));
        for(int i=0;i<3;i++) b.reserve(ModelStage.RUNNING_ANALYST);
        assertThat(b.stopOptional()).isTrue();
        assertThatThrownBy(()->b.reserve(ModelStage.WEATHER_ADVISOR)).isInstanceOf(MultiAgentException.class);
        b.reserve(ModelStage.SUPERVISOR_SYNTHESIS);
        assertThatThrownBy(()->b.reserve(ModelStage.SUPERVISOR_SYNTHESIS)).isInstanceOf(MultiAgentException.class);
        assertThat(b.modelCalls()).isEqualTo(4);
    }
    @Test void knownTokensTriggerSoftStopWhileSynthesisRemainsAvailable() {
        var b=budget(properties(PerformanceMode.BASELINE,16,100));
        b.reserve(ModelStage.RUNNING_ANALYST);
        record(b,ModelStage.RUNNING_ANALYST,new DefaultUsage(90,11));
        assertThat(b.softBudgetExceeded()).isTrue();
        assertThatThrownBy(()->b.reserve(ModelStage.WEATHER_ADVISOR)).isInstanceOf(MultiAgentException.class);
        b.reserve(ModelStage.SUPERVISOR_SYNTHESIS);
    }
    @Test void emptyInvalidAndMixedUsageStayUnknownWithKnownSubtotalOnly() {
        var b=budget(properties(PerformanceMode.BASELINE,16,30000));
        assertThat(record(b,ModelStage.RUNNING_ANALYST,new EmptyUsage()).inputTokens()).isNull();
        assertThat(record(b,ModelStage.RUNNING_ANALYST,new DefaultUsage(-1,4)).usageStatus()).isEqualTo(UsageStatus.UNKNOWN);
        assertThat(record(b,ModelStage.RUNNING_ANALYST,new DefaultUsage(10,2,99)).usageStatus()).isEqualTo(UsageStatus.UNKNOWN);
        record(b,ModelStage.KNOWLEDGE_COACH,new DefaultUsage(10,2));
        assertThat(b.collector().usageComplete()).isFalse();
        assertThat(b.collector().knownTotalTokens()).isEqualTo(12);
        var cost=new CostEstimator(b.properties().pricing()).estimate(b.collector().records());
        assertThat(cost.status()).isEqualTo(CostEstimate.Status.UNKNOWN);
        assertThat(cost.amount()).isNull();
    }
    @Test void normalizedMissingCompletionCannotBecomeKnownOrFree() {
        var b=budget(properties(PerformanceMode.BASELINE,16,30000));
        var missing=record(b,ModelStage.KNOWLEDGE_COACH,new DefaultUsage(100,null,null));
        assertThat(missing.usageStatus()).isEqualTo(UsageStatus.UNKNOWN);
        assertThat(missing.outputTokens()).isNull();
        assertThat(new CostEstimator(b.properties().pricing()).estimate(List.of(missing)).status()).isEqualTo(CostEstimate.Status.UNKNOWN);
    }
    @Test void nativeExplicitZeroCompletionIsKnownWithoutRetainingNativeUsage() {
        var nativeUsage=com.openai.models.completions.CompletionUsage.builder().promptTokens(100).completionTokens(0).totalTokens(100).build();
        var b=budget(properties(PerformanceMode.BASELINE,16,30000));
        var r=record(b,ModelStage.KNOWLEDGE_COACH,new DefaultUsage(100,0,100,nativeUsage));
        assertThat(r.usageStatus()).isEqualTo(UsageStatus.KNOWN);
        assertThat(r.outputTokens()).isZero();
    }
    @Test void smallerGlobalOutputCapClampsStageLimitsRatherThanRejectingValidConfiguration() {
        var p=properties(PerformanceMode.OPTIMIZED,16,30000);
        var smaller=new cn.watsonzhu.runagent.config.PerformanceProperties(p.mode(),16,500,30000,p.models(),Map.of(ModelStage.SUPERVISOR_SYNTHESIS,1200),p.context(),p.pricing(),Map.of());
        assertThat(new ModelRoutePolicy(smaller).resolve(ModelStage.SUPERVISOR_SYNTHESIS).maxOutputTokens()).isEqualTo(500);
    }
    @Test void missingPriceAndUnpricedCacheUsageProduceUnknownCost() {
        var b=budget(properties(PerformanceMode.OPTIMIZED,16,30000));
        var fast=record(b,ModelStage.RUNNING_ANALYST,new DefaultUsage(10,2));
        var cache=record(b,ModelStage.KNOWLEDGE_COACH,new DefaultUsage(10,2,12,null,5L,null));
        var estimator=new CostEstimator(b.properties().pricing());
        assertThat(estimator.estimate(List.of(fast)).status()).isEqualTo(CostEstimate.Status.UNKNOWN);
        assertThat(estimator.estimate(List.of(cache)).status()).isEqualTo(CostEstimate.Status.UNKNOWN);
    }
    @Test void routingContextIsShortButSynthesisContextRemainsFull() {
        String report="x".repeat(2500);
        var optimized=new ContextBudgetPolicy(properties(PerformanceMode.OPTIMIZED,16,30000));
        var baseline=new ContextBudgetPolicy(properties(PerformanceMode.BASELINE,16,30000));
        assertThat(optimized.routingReport(report)).hasSize(600);
        assertThat(baseline.routingReport(report)).isEqualTo(report);
        assertThat(optimized.synthesisReport(report)).isEqualTo(report);
    }
    @Test void knowledgeContextUsesRankedDocumentBoundariesAndKeepsSourceWithinLimit() {
        var p=properties(PerformanceMode.OPTIMIZED,16,30000);
        var bounded=new ContextBudgetPolicy(new PerformanceProperties(p.mode(),16,1500,30000,p.models(),Map.of(),new PerformanceProperties.Context(600,100),p.pricing(),Map.of()));
        var docs=List.of(Document.builder().text("A".repeat(30)).metadata(Map.of("sourceTitle","First")).build(),
                Document.builder().text("😀".repeat(100)).metadata(Map.of("sourceTitle","Second")).build(),
                Document.builder().text("must not appear").metadata(Map.of("sourceTitle","Third")).build());
        String context=bounded.knowledgeEvidence(docs);
        assertThat(context.length()).isLessThanOrEqualTo(100);
        assertThat(context).startsWith("Source: First\n").contains("Source: Second\n").doesNotContain("Third");
        assertThat(Character.isHighSurrogate(context.charAt(context.length()-1))).isFalse();
    }
    @Test void concurrentScopesDoNotShareBudgetsAndAlwaysClearAfterFailure() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=budget(properties(PerformanceMode.BASELINE,16,30000));
            var b=budget(properties(PerformanceMode.BASELINE,16,30000));
            var barrier=new CyclicBarrier(2);
            var fa=pool.submit(()->scopedCalls(a,8,barrier));
            var fb=pool.submit(()->scopedCalls(b,3,barrier));
            fa.get(3,TimeUnit.SECONDS); fb.get(3,TimeUnit.SECONDS);
            assertThat(a.modelCalls()).isEqualTo(8); assertThat(b.modelCalls()).isEqualTo(3);
            assertThat(pool.submit(ExecutionScope::current).get()).isNull();
        }
        var b=budget(properties(PerformanceMode.BASELINE,16,30000));
        assertThatThrownBy(()->{try(var scope=ExecutionScope.open(b)){throw new IllegalStateException();}}).isInstanceOf(IllegalStateException.class);
        assertThat(ExecutionScope.current()).isNull();
    }
    private static void scopedCalls(ExecutionBudget budget,int count,CyclicBarrier barrier) {
        try(var scope=ExecutionScope.open(budget)) {
            barrier.await(2,TimeUnit.SECONDS);
            for(int i=0;i<count;i++) ExecutionScope.require().reserve(ModelStage.KNOWLEDGE_COACH);
        } catch(Exception e){throw new RuntimeException(e);}
    }
    @Test void deadlineReusesExistingControlAndBlocksBeforeReservation() {
        var control=new MultiAgentExecutionControl(Duration.ofSeconds(10));
        var b=new ExecutionBudget("safe",control,properties(PerformanceMode.BASELINE,16,30000));
        control.cancel();
        assertThatThrownBy(()->b.reserve(ModelStage.SUPERVISOR_SYNTHESIS)).isInstanceOf(MultiAgentException.class);
        assertThat(b.modelCalls()).isZero();
    }
}
