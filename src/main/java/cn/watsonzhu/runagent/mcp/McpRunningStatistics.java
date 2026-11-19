package cn.watsonzhu.runagent.mcp;

import java.time.LocalDate;
import java.util.Map;

import cn.watsonzhu.runagent.exception.RunningDataUnavailableException;
import cn.watsonzhu.runagent.model.running.DistanceType;
import cn.watsonzhu.runagent.model.running.PersonalBest;
import cn.watsonzhu.runagent.model.running.RunningSummary;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class McpRunningStatistics {
    private final ObjectProvider<McpCatalog> catalog;
    private final ObjectMapper json;

    public McpRunningStatistics(ObjectProvider<McpCatalog> catalog, ObjectMapper json) {
        this.catalog = catalog;
        this.json = json;
    }

    public Snapshot<RunningSummary> summary(LocalDate start, LocalDate end) {
        try {
            JsonNode result = call("getRunningSummary", Map.of("startDate", start.toString(), "endDate", end.toString()));
            if (!start.toString().equals(result.path("startDate").asString())
                    || !end.toString().equals(result.path("endDate").asString())
                    || !result.path("runCount").isIntegralNumber()
                    || !result.path("runCount").canConvertToInt()) {
                throw unavailable();
            }
            int count = result.path("runCount").intValue();
            if (count < 0) throw unavailable();
            var summary = new RunningSummary(start, end, count, distance(result.path("totalDistanceKm")),
                    nonnegativeLong(result.path("totalDurationSeconds")));
            return new Snapshot<>(summary, dataThrough(result));
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    public Snapshot<PersonalBest> personalBest(DistanceType type) {
        try {
            JsonNode result = call("getPersonalBest", Map.of("distanceType", type.name()));
            if (!type.name().equals(result.path("distanceType").asString())
                    || !result.path("approximate").isBoolean() || !result.path("approximate").booleanValue()) {
                throw unavailable();
            }
            LocalDate through = dataThrough(result);
            return switch (result.path("calculationBasis").asString()) {
                case "NO_MATCHING_ACTIVITY" -> new Snapshot<>(null, through);
                case "WHOLE_ACTIVITY_DISTANCE_MATCH" -> {
                    LocalDate date = date(result.path("date"));
                    double km = distance(result.path("activityDistanceKm"));
                    long seconds = nonnegativeLong(result.path("durationSeconds"));
                    long pace = nonnegativeLong(result.path("averagePaceSecondsPerKm"));
                    if (km <= 0 || pace > Integer.MAX_VALUE) throw unavailable();
                    var pb = new PersonalBest(type, date, km, seconds, (int) pace, true, "WHOLE_ACTIVITY_DISTANCE_MATCH");
                    yield new Snapshot<>(pb, through);
                }
                default -> throw unavailable();
            };
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    public boolean hasRunningTools() {
        var available = catalog.getIfAvailable();
        return available != null && McpPolicy.RUNNING_TOOLS.stream()
                .allMatch(tool -> available.findTool("running", tool).isPresent());
    }

    private JsonNode call(String tool, Map<String, String> arguments) {
        var available = catalog.getIfAvailable();
        if (available == null) throw unavailable();
        var callback = available.findTool("running", tool).orElseThrow(McpRunningStatistics::unavailable);
        var blocks = json.readTree(callback.call(json.writeValueAsString(arguments), new ToolContext(Map.of())));
        // Spring AI 2.0.1 exposes a JSON array of MCP content blocks, with or without the text discriminator.
        if (!blocks.isArray() || blocks.size() != 1) throw unavailable();
        var block = blocks.get(0);
        if (!block.isObject() || (block.has("type") && !"text".equals(block.path("type").asString()))
                || !block.path("text").isString()) {
            throw unavailable();
        }
        JsonNode result = json.readTree(block.path("text").asString());
        if (!result.isObject() || result.has("error")) throw unavailable();
        return result;
    }

    private static long nonnegativeLong(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw unavailable();
        return value.longValue();
    }

    private static double distance(JsonNode value) {
        if (!value.isNumber() || !Double.isFinite(value.doubleValue()) || value.doubleValue() < 0) throw unavailable();
        return value.doubleValue();
    }

    private static LocalDate dataThrough(JsonNode result) {
        JsonNode value = result.path("dataThrough");
        return value.isMissingNode() || value.isNull() ? null : date(value);
    }

    private static LocalDate date(JsonNode value) {
        if (!value.isString()) throw unavailable();
        return LocalDate.parse(value.asString());
    }

    private static RunningDataUnavailableException unavailable() {
        return new RunningDataUnavailableException("Running data is unavailable");
    }

    public record Snapshot<T>(T value, LocalDate through) {}
}
