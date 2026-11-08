package cn.watsonzhu.runagent.multiagent;

import java.time.Duration;
import java.util.Map;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Preserve callback schemas and keep all Host execution context out of delegates. */
public final class SpecialistTracingToolCallback implements ToolCallback {
    private static final Logger log = LoggerFactory.getLogger(SpecialistTracingToolCallback.class);
    private static final JsonMapper JSON = new JsonMapper();
    private final ToolCallback delegate;

    public SpecialistTracingToolCallback(ToolCallback delegate) { this.delegate = delegate; }
    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
    @Override public String call(String input) { return call(input, null); }
    @Override public String call(String input, ToolContext context) {
        Object value = context == null ? null : context.getContext().get(MultiAgentExecutionTrace.CONTEXT_KEY);
        if (!(value instanceof MultiAgentExecutionTrace trace))
            throw new MultiAgentException(MultiAgentException.Kind.EXECUTION_FAILED);
        trace.control().checkActive();
        int step = trace.nextStep();
        long started = System.nanoTime();
        String status = "FAILED";
        try {
            // Spring AI 2.0.1 requires a nonempty context for local methods declaring ToolContext.
            // Remote adapters still receive an empty context, with the existing noOp MCP converter.
            Map<String, Object> localContext = delegate instanceof MethodToolCallback
                    ? Map.of("requestType", "multi-agent") : Map.of();
            String result = delegate.call(enforceHints(input, trace), new ToolContext(localContext));
            trace.control().checkActive();
            if (result == null || result.isBlank() || remoteError(result))
                throw new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE);
            JsonNode evidence = JSON.readTree(result);
            boolean locationSearch = getToolDefinition().name().endsWith("openmeteo_search_locations");
            boolean recentRuns = getToolDefinition().name().endsWith("getRecentRuns");
            if (delegate instanceof SyncMcpToolCallback && evidence.isArray() && evidence.isEmpty()
                    || !usableEvidenceValue(evidence, locationSearch, recentRuns))
                throw new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE);
            if (trace.specialist() == Specialist.WEATHER_ADVISOR) {
                if (getToolDefinition().name().endsWith("openmeteo_search_locations")) {
                    rememberLocations(evidence, trace);
                    if (!trace.hasWeatherLocations()) throw new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE);
                }
            }
            trace.succeeded(getToolDefinition().name());
            status = "SUCCESS";
            return result;
        } catch (RuntimeException exception) {
            trace.control().checkActive();
            trace.failed();
            // The model may consume this safe marker, but the specialist can no longer publish a SUCCESS report.
            return "{\"error\":\"SPECIALIST_EVIDENCE_UNAVAILABLE\"}";
        } finally {
            log.info("multiAgentExecutionId={} specialist={} step={} tool={} status={} latencyMs={}", trace.executionId(),
                    trace.specialist().observationName(), step, getToolDefinition().name(), status,
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
    }

    private String enforceHints(String input, MultiAgentExecutionTrace trace) {
        String name = getToolDefinition().name();
        boolean dates = trace.startDate() != null && (name.endsWith("getRunningSummary") || name.endsWith("getRecentRuns"));
        boolean location = trace.location() != null && !trace.location().isBlank() && name.endsWith("openmeteo_search_locations");
        boolean weatherQuery = name.endsWith("openmeteo_get_forecast") || name.endsWith("openmeteo_get_air_quality");
        if (!dates && !location && !weatherQuery) return input;
        JsonNode parsed = JSON.readTree(input);
        if (!(parsed instanceof ObjectNode arguments)) throw new MultiAgentException(MultiAgentException.Kind.EXECUTION_FAILED);
        if (dates) {
            arguments.put("startDate", trace.startDate().toString());
            arguments.put("endDate", trace.endDate().toString());
        }
        if (location) arguments.put("name", trace.location());
        if (weatherQuery && (!arguments.path("latitude").isNumber() || !arguments.path("longitude").isNumber()
                || !trace.acceptsWeatherLocation(arguments.path("latitude").asDouble(), arguments.path("longitude").asDouble())))
            throw new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE);
        return JSON.writeValueAsString(arguments);
    }

    private static void rememberLocations(JsonNode node, MultiAgentExecutionTrace trace) {
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            try { rememberLocations(JSON.readTree(node.asText()), trace); }
            catch (RuntimeException ignored) { }
        } else if (node.isObject()) {
            if (node.path("latitude").isNumber() && node.path("longitude").isNumber()) {
                double latitude = node.path("latitude").asDouble();
                double longitude = node.path("longitude").asDouble();
                if (Double.isFinite(latitude) && Double.isFinite(longitude) && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180)
                    trace.allowWeatherLocation(latitude, longitude);
            }
            node.forEach(child -> rememberLocations(child, trace));
        } else if (node.isArray()) node.forEach(child -> rememberLocations(child, trace));
    }

    private static boolean usableEvidenceValue(JsonNode node, boolean locationSearch, boolean emptyRunsAllowed) {
        if (node == null || node.isNull()) return false;
        if (node.isTextual()) {
            if (node.asText().isBlank()) return false;
            try { return usableEvidenceValue(JSON.readTree(node.asText()), locationSearch, emptyRunsAllowed); }
            catch (RuntimeException ignored) { return true; }
        }
        if (node.isObject()) {
            for (var entry : node.properties())
                if (!java.util.Set.of("type", "latitude", "longitude", "timezone", "timezone_abbreviation", "generationtime_ms", "utc_offset_seconds", "elevation").contains(entry.getKey())
                        && usableEvidenceValue(entry.getValue(), locationSearch, emptyRunsAllowed)) return true;
            // A geocoder result can consist solely of its coordinate pair.
            return locationSearch && node.path("latitude").isNumber() && node.path("longitude").isNumber();
        }
        if (node.isArray()) {
            if (node.isEmpty()) return emptyRunsAllowed;
            for (JsonNode child : node) if (usableEvidenceValue(child, locationSearch, emptyRunsAllowed)) return true;
            return false;
        }
        return node.isNumber() || node.isBoolean();
    }

    private static boolean remoteError(String result) {
        JsonNode parsed;
        try { parsed = JSON.readTree(result); }
        catch (RuntimeException ignored) { return false; }
        if (parsed == null || parsed.isNull()) return true;
        if (parsed.isObject()) return parsed.has("error") || parsed.path("isError").asBoolean(false);
        if (parsed.isArray()) {
            for (JsonNode block : parsed) {
                if (block.has("error") || block.path("isError").asBoolean(false)) return true;
                if (block.has("text") && remoteError(block.path("text").asText())) return true;
            }
        }
        return false;
    }
}
