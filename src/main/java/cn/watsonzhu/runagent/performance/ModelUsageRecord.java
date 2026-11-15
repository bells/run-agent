package cn.watsonzhu.runagent.performance;

import java.time.Duration;

/** Safe metadata only: never retain response, native usage, messages or tool context. */
public record ModelUsageRecord(String executionId, ModelStage stage, ModelTier tier, String model,
        Integer inputTokens, Integer outputTokens, Integer totalTokens, Duration duration,
        ModelCallStatus status, UsageStatus usageStatus, Long cacheReadInputTokens, Long cacheWriteInputTokens,
        FinishReason finishReason, Integer outputCharacters, Long reasoningTokens, ModelReasoningEffort reasoningEffort) {
    public enum FinishReason { STOP, OUTPUT_LIMIT, TOOL_CALLS, FILTERED, UNKNOWN }
}
