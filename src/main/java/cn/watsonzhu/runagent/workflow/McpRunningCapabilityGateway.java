package cn.watsonzhu.runagent.workflow;

import java.time.LocalDate;
import java.util.Map;
import cn.watsonzhu.runagent.exception.WorkflowException;
import cn.watsonzhu.runagent.mcp.McpCatalog;
import cn.watsonzhu.runagent.model.running.RunningSummary;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "run-agent.running-tools.provider", havingValue = "mcp")
public class McpRunningCapabilityGateway implements RunningCapabilityGateway {
    private final ObjectProvider<McpCatalog> catalog;
    private final ObjectMapper json;

    public McpRunningCapabilityGateway(ObjectProvider<McpCatalog> catalog, ObjectMapper json) {
        this.catalog = catalog;
        this.json = json;
    }

    @Override public RunningSummary getRunningSummary(LocalDate startDate, LocalDate endDate) {
        try {
            var available = catalog.getIfAvailable();
            if (available == null) throw unavailable();
            var callback = available.findTool("running", "getRunningSummary").orElseThrow(McpRunningCapabilityGateway::unavailable);
            String arguments = json.writeValueAsString(Map.of("startDate", startDate.toString(), "endDate", endDate.toString()));
            String result = callback.call(arguments, new ToolContext(Map.of()));
            // Spring AI 2.0.1 serializes MCP content blocks, not structuredContent or the Java DTO directly.
            JsonNode blocks = json.readTree(result);
            if (!blocks.isArray() || blocks.size() != 1) throw unavailable();
            JsonNode block = blocks.get(0);
            // The 2.0.1 callback's JsonHelper omits the polymorphic type discriminator on text blocks.
            if (!block.isObject() || block.has("type") && !"text".equals(block.path("type").asString())
                    || !block.path("text").isString()) throw unavailable();
            JsonNode summary = json.readTree(blocks.get(0).path("text").asString());
            if (!summary.isObject() || summary.has("error")
                    || !startDate.toString().equals(summary.path("startDate").asString())
                    || !endDate.toString().equals(summary.path("endDate").asString())
                    || !summary.path("runCount").isIntegralNumber() || !summary.path("runCount").canConvertToInt()
                    || !summary.path("totalDurationSeconds").isIntegralNumber() || !summary.path("totalDurationSeconds").canConvertToLong()
                    || !summary.path("totalDistanceKm").isNumber()) throw unavailable();
            int count = summary.path("runCount").intValue();
            long seconds = summary.path("totalDurationSeconds").longValue();
            double distance = summary.path("totalDistanceKm").doubleValue();
            if (count < 0 || seconds < 0 || distance < 0 || !Double.isFinite(distance)) throw unavailable();
            return new RunningSummary(startDate, endDate, count, distance, seconds);
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private static WorkflowException unavailable() {
        return new WorkflowException(WorkflowException.Kind.DEPENDENCY_UNAVAILABLE);
    }
}
