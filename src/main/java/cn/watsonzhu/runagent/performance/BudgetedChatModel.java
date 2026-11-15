package cn.watsonzhu.runagent.performance;

import java.time.Duration;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Intercepts each model round beneath ToolCallingAdvisor, never the aggregate final response. */
public final class BudgetedChatModel implements ChatModel {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BudgetedChatModel.class);
    private final ChatModel delegate;
    private final ModelStage stage;
    private final ObservationRegistry observations;
    public BudgetedChatModel(ChatModel delegate, ModelStage stage, ObservationRegistry observations) {
        this.delegate = delegate; this.stage = stage; this.observations = observations;
    }
    @Override public ChatOptions getOptions() { return delegate.getOptions(); }
    @Override public ChatResponse call(Prompt prompt) {
        var budget = ExecutionScope.require();
        var route = new ModelRoutePolicy(budget.properties()).resolve(stage);
        budget.reserve(stage);
        long started = System.nanoTime();
        ChatResponse response = null;
        ModelCallStatus status = ModelCallStatus.FAILED;
        var observation = Observation.createNotStarted("runagent.performance.model", observations)
                .lowCardinalityKeyValue("performance.mode", budget.properties().mode().name())
                .lowCardinalityKeyValue("model.stage", stage.name())
                .lowCardinalityKeyValue("model.tier", route.tier().name())
                .lowCardinalityKeyValue("model.reasoning_effort", route.reasoningEffort().name())
                .highCardinalityKeyValue("model.name", route.model())
                .highCardinalityKeyValue("multiAgentExecutionId", budget.executionId()).start();
        try (var scope = observation.openScope()) {
            // mutate() preserves typed options, tool callbacks, tool context and structured-output settings in 2.0.1.
            ChatOptions original = prompt.getOptions() == null ? delegate.getOptions() : prompt.getOptions();
            var options = original.mutate().model(route.model()).maxTokens(route.maxOutputTokens()).build();
            options = reasoningOptions(options, route.reasoningEffort());
            response = delegate.call(new Prompt(prompt.getInstructions(), options));
            budget.checkActive();
            if (response == null) throw new MultiAgentException(MultiAgentException.Kind.EXECUTION_FAILED);
            var output = response.getMetadata().getUsage().getCompletionTokens();
            boolean truncated = ModelUsageCollector.finishReason(response) == ModelUsageRecord.FinishReason.OUTPUT_LIMIT;
            if (truncated || output != null && output > route.maxOutputTokens()) budget.outputExceeded();
            status = ModelCallStatus.SUCCESS;
            return response;
        } catch (RuntimeException exception) {
            budget.checkActive();
            if (exception instanceof MultiAgentException failure) throw failure;
            throw new MultiAgentException(MultiAgentException.Kind.EXECUTION_FAILED);
        } finally {
            var record = budget.collector().record(stage, route, response, Duration.ofNanos(System.nanoTime() - started), status);
            budget.reconcile();
            observation.lowCardinalityKeyValue("outcome", status.name())
                    .lowCardinalityKeyValue("usage.status", record.usageStatus().name()).stop();
            log.info("multiAgentExecutionId={} stage={} tier={} model={} input={} output={} total={} durationMs={} status={} usageStatus={} finishReason={} outputChars={} reasoningTokens={} reasoningEffort={}",
                    budget.executionId(), stage, route.tier(), route.model(), record.inputTokens() == null ? "UNKNOWN" : record.inputTokens(),
                    record.outputTokens() == null ? "UNKNOWN" : record.outputTokens(), record.totalTokens() == null ? "UNKNOWN" : record.totalTokens(),
                    record.duration().toMillis(), status, record.usageStatus(), record.finishReason(),
                    record.outputCharacters() == null ? "UNKNOWN" : record.outputCharacters(),
                    record.reasoningTokens() == null ? "UNKNOWN" : record.reasoningTokens(), record.reasoningEffort());
        }
    }
    private static ChatOptions reasoningOptions(ChatOptions options, ModelReasoningEffort effort) {
        if (effort == ModelReasoningEffort.PROVIDER_DEFAULT) return options;
        if (!(options instanceof OpenAiChatOptions openAi))
            throw new IllegalStateException("Stage reasoning overrides require OpenAI-compatible options");
        var builder = openAi.mutate().reasoningEffort(effort.apiValue());
        // Legacy extra-body fields can override typed options or DeepSeek's reasoning_effort.
        if (openAi.getExtraBody() != null && (openAi.getExtraBody().containsKey("thinking")
                || openAi.getExtraBody().containsKey("reasoning_effort"))) {
            var body = new java.util.HashMap<>(openAi.getExtraBody());
            if (body.containsKey("reasoning_effort")) body.put("reasoning_effort", effort.apiValue());
            if (body.containsKey("thinking"))
                body.put("thinking", java.util.Map.of("type", effort == ModelReasoningEffort.NONE ? "disabled" : "enabled"));
            builder.extraBody(body);
        }
        return builder.build();
    }
}
