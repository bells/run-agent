package cn.watsonzhu.runagent.performance;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Internal immutable result; deliberately separate from the public MultiAgentResponse. */
public record PerformanceSnapshot(String executionId, PerformanceMode mode, Duration totalLatency, int modelCalls,
        Map<ModelStage, StageSummary> stageBreakdown, long knownInputTokens, long knownOutputTokens,
        long knownTotalTokens, boolean usageComplete, int unknownCalls, String tokenEnforcement,
        CostEstimate estimatedCost, boolean softBudgetExceeded, Set<BudgetReason> budgetReasons,
        List<ModelUsageRecord> calls) {
    public PerformanceSnapshot { stageBreakdown = Map.copyOf(stageBreakdown); calls = List.copyOf(calls); budgetReasons = Set.copyOf(budgetReasons); }
    public record StageSummary(int modelCalls, Duration stageDuration, Duration modelDuration,
            long knownInputTokens, long knownOutputTokens, long knownTotalTokens, int unknownCalls) { }
    public static PerformanceSnapshot capture(ExecutionBudget budget, CostEstimator costs) {
        var calls = budget.collector().records();
        var stages = new EnumMap<ModelStage, StageSummary>(ModelStage.class);
        for (var stage : ModelStage.values()) {
            var records = calls.stream().filter(r -> r.stage() == stage).toList();
            var modelTime = records.stream().map(ModelUsageRecord::duration).reduce(Duration.ZERO, Duration::plus);
            stages.put(stage, new StageSummary(records.size(), budget.stageDurations().getOrDefault(stage, Duration.ZERO), modelTime,
                    records.stream().filter(r -> r.inputTokens() != null).mapToLong(ModelUsageRecord::inputTokens).sum(),
                    records.stream().filter(r -> r.outputTokens() != null).mapToLong(ModelUsageRecord::outputTokens).sum(),
                    records.stream().filter(r -> r.totalTokens() != null).mapToLong(ModelUsageRecord::totalTokens).sum(),
                    (int) records.stream().filter(r -> r.usageStatus() == UsageStatus.UNKNOWN).count()));
        }
        boolean complete = budget.collector().usageComplete();
        return new PerformanceSnapshot(budget.executionId(), budget.properties().mode(), budget.elapsed(), budget.modelCalls(), stages,
                budget.collector().knownInputTokens(), budget.collector().knownOutputTokens(), budget.collector().knownTotalTokens(), complete,
                (int) calls.stream().filter(r -> r.usageStatus() == UsageStatus.UNKNOWN).count(), complete ? "ACTIVE" : "DEGRADED",
                costs.estimate(calls), budget.softBudgetExceeded(), budget.reasons(), calls);
    }
}
