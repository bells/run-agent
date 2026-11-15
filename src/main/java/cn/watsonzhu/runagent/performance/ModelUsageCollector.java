package cn.watsonzhu.runagent.performance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.metadata.EmptyUsage;
import com.openai.models.completions.CompletionUsage;
import org.springframework.ai.chat.model.ChatResponse;

public final class ModelUsageCollector {
    private final String executionId;
    private final List<ModelUsageRecord> records = new ArrayList<>();
    public ModelUsageCollector(String executionId) { this.executionId = executionId; }
    public synchronized ModelUsageRecord record(ModelStage stage, ModelRoute route, ChatResponse response,
                                                Duration duration, ModelCallStatus status) {
        var usage = response == null ? null : response.getMetadata().getUsage();
        Integer input = usage == null ? null : usage.getPromptTokens();
        Integer output = usage == null ? null : usage.getCompletionTokens();
        Integer total = usage == null ? null : usage.getTotalTokens();
        // DefaultUsage can normalize missing values to zero; all-zero or missing input is not consumption evidence.
        boolean known = usage != null && !(usage instanceof EmptyUsage) && input != null && output != null && total != null
                && input > 0 && output >= 0 && total > 0 && (long) input + output == total
                && (output > 0 || explicitZeroCompletion(usage, input, total));
        var item = new ModelUsageRecord(executionId, stage, route.tier(), route.model(), known ? input : null,
                known ? output : null, known ? total : null, duration, status, known ? UsageStatus.KNOWN : UsageStatus.UNKNOWN,
                usage == null ? null : usage.getCacheReadInputTokens(), usage == null ? null : usage.getCacheWriteInputTokens(),
                finishReason(response), outputCharacters(response), known ? reasoningTokens(usage, output) : null, route.reasoningEffort());
        records.add(item);
        return item;
    }
    static ModelUsageRecord.FinishReason finishReason(ChatResponse response) {
        if (response == null || response.getResults().isEmpty()) return ModelUsageRecord.FinishReason.UNKNOWN;
        ModelUsageRecord.FinishReason first = ModelUsageRecord.FinishReason.UNKNOWN;
        for (int i = 0; i < response.getResults().size(); i++) {
            String reason = String.valueOf(response.getResults().get(i).getMetadata().getFinishReason())
                    .strip().toLowerCase(java.util.Locale.ROOT);
            var normalized = switch (reason) {
                case "length", "max_tokens", "max_output_tokens" -> ModelUsageRecord.FinishReason.OUTPUT_LIMIT;
                case "stop" -> ModelUsageRecord.FinishReason.STOP;
                case "tool_calls", "function_call" -> ModelUsageRecord.FinishReason.TOOL_CALLS;
                case "content_filter" -> ModelUsageRecord.FinishReason.FILTERED;
                default -> ModelUsageRecord.FinishReason.UNKNOWN;
            };
            if (normalized == ModelUsageRecord.FinishReason.OUTPUT_LIMIT) return normalized;
            if (i == 0) first = normalized;
        }
        return first;
    }
    private static Integer outputCharacters(ChatResponse response) {
        if (response == null) return null;
        int count = 0;
        for (var generation : response.getResults()) {
            String text = generation.getOutput().getText();
            if (text != null) count += text.codePointCount(0, text.length());
        }
        return count;
    }
    private static Long reasoningTokens(org.springframework.ai.chat.metadata.Usage usage, int outputTokens) {
        if (!(usage.getNativeUsage() instanceof CompletionUsage nativeUsage)) return null;
        try {
            Long count = nativeUsage.completionTokensDetails().flatMap(CompletionUsage.CompletionTokensDetails::reasoningTokens).orElse(null);
            return count != null && count >= 0 && count <= outputTokens ? count : null;
        } catch (RuntimeException ignored) { return null; }
    }
    private static boolean explicitZeroCompletion(org.springframework.ai.chat.metadata.Usage usage, int input, int total) {
        // Spring AI normalizes a missing completion count to zero. Only native explicit evidence disambiguates zero.
        if (!(usage.getNativeUsage() instanceof CompletionUsage nativeUsage)) return false;
        try { return nativeUsage.completionTokens() == 0 && nativeUsage.promptTokens() == input && nativeUsage.totalTokens() == total; }
        catch (RuntimeException ignored) { return false; }
    }
    public synchronized List<ModelUsageRecord> records() { return List.copyOf(records); }
    public synchronized boolean usageComplete() { return !records.isEmpty() && records.stream().allMatch(r -> r.usageStatus() == UsageStatus.KNOWN); }
    public synchronized long knownInputTokens() { return records.stream().filter(r -> r.usageStatus() == UsageStatus.KNOWN).mapToLong(ModelUsageRecord::inputTokens).sum(); }
    public synchronized long knownOutputTokens() { return records.stream().filter(r -> r.usageStatus() == UsageStatus.KNOWN).mapToLong(ModelUsageRecord::outputTokens).sum(); }
    public synchronized long knownTotalTokens() { return records.stream().filter(r -> r.usageStatus() == UsageStatus.KNOWN).mapToLong(ModelUsageRecord::totalTokens).sum(); }
}
