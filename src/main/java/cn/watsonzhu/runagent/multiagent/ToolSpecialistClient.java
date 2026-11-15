package cn.watsonzhu.runagent.multiagent;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import cn.watsonzhu.runagent.config.MultiAgentProperties;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import cn.watsonzhu.runagent.mcp.StructuredWeatherToolCallback;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/** Each specialist owns an independent instance and client with its own immutable capability snapshot. */
final class ToolSpecialistClient {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ToolSpecialistClient.class);
    private final ChatClient client;
    private final ToolCallback[] callbacks;
    private final MultiAgentProperties properties;
    private final boolean available;

    ToolSpecialistClient(ChatModel model, ToolCallbackProvider tools, Set<String> allowed, ToolCallingManager manager,
                         MultiAgentProperties properties, ObservationRegistry observations) {
        ToolCallback[] snapshot = tools.getToolCallbacks();
        var names = Arrays.stream(snapshot).map(ToolSpecialistClient::originalToolName).toList();
        if (!allowed.containsAll(names) || names.stream().distinct().count() != names.size())
            throw new IllegalArgumentException("Unexpected specialist capability");
        this.available = Set.copyOf(names).equals(allowed);
        this.callbacks = Arrays.stream(snapshot).map(SpecialistTracingToolCallback::new).toArray(ToolCallback[]::new);
        this.client = ChatClient.builder(model, observations, null, null,
                ToolCallingAdvisor.builder().toolCallingManager(manager)).build();
        this.properties = properties;
    }
    private static String originalToolName(ToolCallback callback) {
        if (callback instanceof SyncMcpToolCallback mcp) return mcp.getOriginalToolName();
        if (callback instanceof StructuredWeatherToolCallback weather) return weather.originalToolName();
        return callback.getToolDefinition().name();
    }
    boolean isAvailable() { return available; }
    String analyze(String system, String context, MultiAgentExecutionTrace trace) {
        trace.control().checkActive();
        if (!available) throw new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE);
        var response = client.prompt().messages(new SystemMessage(system + "\nReturn at most " + properties.maxReportChars() + " characters."),
                        new UserMessage(context))
                .tools((ToolCallbackProvider) () -> callbacks.clone())
                .toolContext(Map.of(MultiAgentExecutionTrace.CONTEXT_KEY, trace)).call().chatResponse();
        trace.control().checkActive();
        if (trace.hasFailure()) throw new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE);
        if (response != null && response.getResult() != null && ToolCallLimitExceededException.FINISH_REASON.equals(
                response.getResult().getMetadata().getFinishReason())) {
            log.info("multiAgentExecutionId={} specialist={} phase=REPORT status=FAILED reason=TOOL_CALL_LIMIT successfulToolCalls={}",
                    trace.executionId(), trace.specialist().observationName(), trace.successfulToolCalls());
            throw new MultiAgentException(MultiAgentException.Kind.EXECUTION_FAILED);
        }
        if (trace.successfulToolCalls() == 0) throw new MultiAgentException(MultiAgentException.Kind.INSUFFICIENT_EVIDENCE);
        if (trace.specialist() == Specialist.WEATHER_ADVISOR && !trace.hasWeatherEvidence())
            throw new MultiAgentException(MultiAgentException.Kind.INSUFFICIENT_EVIDENCE);
        return SpecialistReports.bounded(SpecialistReports.text(response), properties.maxReportChars());
    }
}
