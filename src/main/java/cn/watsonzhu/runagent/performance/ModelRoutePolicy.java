package cn.watsonzhu.runagent.performance;

import cn.watsonzhu.runagent.config.PerformanceProperties;

public final class ModelRoutePolicy {
    private final PerformanceProperties properties;
    public ModelRoutePolicy(PerformanceProperties properties) {
        this.properties = properties;
        if (model(ModelTier.DEFAULT).isBlank()) throw new IllegalArgumentException("A trusted default model must be configured");
    }
    public ModelRoute resolve(ModelStage stage) {
        ModelTier requested = properties.mode() == PerformanceMode.BASELINE ? ModelTier.DEFAULT : switch (stage) {
            case SUPERVISOR_ROUTE, RUNNING_ANALYST, WEATHER_ADVISOR -> ModelTier.FAST;
            case KNOWLEDGE_COACH -> ModelTier.DEFAULT;
            case SUPERVISOR_SYNTHESIS -> ModelTier.STRONG;
        };
        ModelTier tier = model(requested).isBlank() ? ModelTier.DEFAULT : requested;
        int stageLimit = switch (stage) {
            case SUPERVISOR_ROUTE -> 256;
            case RUNNING_ANALYST, WEATHER_ADVISOR -> 800;
            case KNOWLEDGE_COACH -> 1000;
            case SUPERVISOR_SYNTHESIS -> 1200;
        };
        int output = properties.mode() == PerformanceMode.BASELINE ? properties.maxOutputTokensPerCall()
                : Math.min(properties.maxOutputTokensPerCall(), properties.stageOutputLimits().getOrDefault(stage, stageLimit));
        var reasoning = properties.mode() == PerformanceMode.BASELINE ? ModelReasoningEffort.PROVIDER_DEFAULT
                : properties.stageReasoningEffort().getOrDefault(stage, ModelReasoningEffort.PROVIDER_DEFAULT);
        return new ModelRoute(requested, tier, model(tier), output, reasoning);
    }
    private String model(ModelTier tier) {
        var config = properties.models().get(tier);
        return config == null ? "" : config.name();
    }
}
