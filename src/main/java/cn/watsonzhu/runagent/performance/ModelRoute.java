package cn.watsonzhu.runagent.performance;

public record ModelRoute(ModelTier requestedTier, ModelTier tier, String model, int maxOutputTokens,
                         ModelReasoningEffort reasoningEffort) { }
