package cn.watsonzhu.runagent.performance;

import cn.watsonzhu.runagent.config.PerformanceProperties;
import cn.watsonzhu.runagent.multiagent.MultiAgentExecutionControl;
import cn.watsonzhu.runagent.observability.RunAgentObservation;
import org.springframework.stereotype.Component;

@Component
public final class PerformanceGovernance {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PerformanceGovernance.class);
    private final PerformanceProperties properties;
    private final RunAgentObservation observations;
    public PerformanceGovernance(PerformanceProperties properties, RunAgentObservation observations) {
        this.properties = properties; this.observations = observations;
        var routes = new ModelRoutePolicy(properties);
        for (var stage : ModelStage.values()) {
            var route = routes.resolve(stage);
            log.info("performanceMode={} stage={} modelTier={} resolvedTier={} model={} maxOutputTokens={} reasoningEffort={}",
                    properties.mode(), stage, route.requestedTier(), route.tier(), route.model(), route.maxOutputTokens(), route.reasoningEffort());
        }
        // Resolve optional-tier fallbacks at startup even when BASELINE is selected.
        for (var tier : java.util.List.of(ModelTier.FAST, ModelTier.STRONG)) {
            var config = properties.models().get(tier);
            if (config == null || config.name().isBlank()) log.info("modelTier={} resolvedTier=DEFAULT", tier);
        }
        log.info("pricingStatus={}", properties.pricing().tiers().values().stream().anyMatch(PerformanceProperties.Price::known) ? "CONFIGURED" : "UNKNOWN");
        log.info("maxModelCalls={} maxOutputTokensPerCall={} softTotalTokens={}",
                properties.maxModelCalls(), properties.maxOutputTokensPerCall(), properties.softTotalTokens());
    }
    public ExecutionBudget start(String executionId, MultiAgentExecutionControl control) { return new ExecutionBudget(executionId, control, properties); }
    public PerformanceSnapshot finish(ExecutionBudget budget) {
        var snapshot = PerformanceSnapshot.capture(budget, new CostEstimator(budget.properties().pricing()));
        observations.performance(snapshot);
        log.info("multiAgentExecutionId={} performanceMode={} modelCalls={} usageStatus={} inputTokens={} outputTokens={} totalTokens={} costStatus={} estimatedCost={} latencyMs={} softBudgetExceeded={} budgetReasons={}",
                snapshot.executionId(), snapshot.mode(), snapshot.modelCalls(), snapshot.usageComplete() ? "KNOWN" : "UNKNOWN",
                snapshot.usageComplete() ? snapshot.knownInputTokens() : "UNKNOWN", snapshot.usageComplete() ? snapshot.knownOutputTokens() : "UNKNOWN",
                snapshot.usageComplete() ? snapshot.knownTotalTokens() : "UNKNOWN", snapshot.estimatedCost().status(),
                snapshot.estimatedCost().amount() == null ? "UNKNOWN" : snapshot.estimatedCost().amount(), snapshot.totalLatency().toMillis(),
                snapshot.softBudgetExceeded(), snapshot.budgetReasons());
        return snapshot;
    }
}
