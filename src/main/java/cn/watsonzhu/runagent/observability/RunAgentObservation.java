package cn.watsonzhu.runagent.observability;

import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import cn.watsonzhu.runagent.exception.AgentLimitException;
import cn.watsonzhu.runagent.exception.WorkflowException;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

@Component
public final class RunAgentObservation {
    private static final Set<String> MODES = Set.of("RUNNING_ONLY", "KNOWLEDGE_ONLY", "COMBINED");
    private static final Set<String> NODES = Set.of("validateInput", "fetchRunningData", "retrieveKnowledge", "analyze", "validateOutput");
    private final ObservationRegistry registry;
    private final MeterRegistry meters;

    public RunAgentObservation(ObservationRegistry registry, MeterRegistry meters) { this.registry = registry; this.meters = meters; }

    public <T> T agent(String executionId, IntSupplier toolCount, Supplier<T> action) {
        try { return observe("runagent.agent", "executionId", executionId, Map.of(), action); }
        finally { DistributionSummary.builder("runagent.agent.tool.calls").register(meters).record(toolCount.getAsInt()); }
    }
    public <T> T workflow(String executionId, String mode, Supplier<T> action) {
        return observe("runagent.workflow", "workflowExecutionId", executionId, Map.of("mode", MODES.contains(mode == null ? "" : mode) ? mode : "invalid"), action);
    }
    public <T> T node(String executionId, String node, Supplier<T> action) {
        if (!NODES.contains(node)) throw new IllegalArgumentException("Unknown workflow node");
        return observe("runagent.workflow.node", "workflowExecutionId", executionId, Map.of("node", node), action);
    }
    public void ragMatches(int count) {
        DistributionSummary.builder("runagent.rag.matches").register(meters).record(count);
        ragOutcome(count == 0 ? "no_context" : "success");
    }
    public void ragOutcome(String outcome) {
        if (!Set.of("success", "no_context", "unavailable", "error").contains(outcome)) throw new IllegalArgumentException("Unknown RAG outcome");
        meters.counter("runagent.rag.requests", "outcome", outcome).increment();
    }
    private <T> T observe(String name, String idKey, String id, Map<String, String> tags, Supplier<T> action) {
        Observation observation = Observation.createNotStarted(name, registry).highCardinalityKeyValue(idKey, id);
        tags.forEach(observation::lowCardinalityKeyValue);
        observation.start();
        String outcome = "success";
        try (var scope = observation.openScope()) { return action.get(); }
        catch (RuntimeException exception) {
            outcome = exception instanceof AgentLimitException ? "limit_exceeded"
                : exception instanceof WorkflowException failure && failure.kind() == WorkflowException.Kind.TIMEOUT ? "timeout" : "error";
            // Export a fixed error, never a provider exception whose message may contain a Prompt or payload.
            observation.error(new OperationFailed());
            throw exception;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome);
            observation.stop();
        }
    }
    private static final class OperationFailed extends RuntimeException {
        private OperationFailed() { super("Operation failed", null, false, false); }
    }
}
