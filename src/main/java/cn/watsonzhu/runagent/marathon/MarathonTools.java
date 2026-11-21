package cn.watsonzhu.runagent.marathon;

import java.time.Duration;
import java.util.function.Supplier;

import cn.watsonzhu.runagent.agent.AgentExecutionTrace;
import cn.watsonzhu.runagent.exception.AiRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import static cn.watsonzhu.runagent.marathon.MarathonData.CalendarResult;
import static cn.watsonzhu.runagent.marathon.MarathonService.PodiumResult;

public final class MarathonTools {
    private static final Logger log = LoggerFactory.getLogger(MarathonTools.class);
    private static final String CALENDAR = "query_upcoming_marathons";
    private static final String PODIUM = "get_marathon_podium_results";
    private final MarathonService service;

    public MarathonTools(MarathonService service) {
        this.service = service;
    }

    @Tool(name = CALENDAR, description = "Look up upcoming marathon or half marathon dates, start times, "
            + "registration deadlines and countdowns in the next three months. Returns dated source evidence.")
    public CalendarResult calendar(
            @ToolParam(required = false, description = "City or province") String city,
            @ToolParam(required = false, description = "Month in YYYY-MM format") String month,
            @ToolParam(required = false, description = "MARATHON or HALF_MARATHON") String distance,
            @ToolParam(required = false, description = "Maximum rows, 1 to 30, default 10") Integer limit,
            @ToolParam(required = false, description = "Known race name or alias for a particular race") String race_name,
            @ToolParam(required = false, description = "True to limit to featured races") Boolean featured,
            @ToolParam(required = false, description = "True to limit to races within China when the user asks for 国内") Boolean domestic_only,
            ToolContext context) {
        return execute(CALENDAR, context, () -> service.calendar(city, month, distance, limit, race_name,
                Boolean.TRUE.equals(featured), Boolean.TRUE.equals(domestic_only)));
    }

    @Tool(name = PODIUM, description = "Read the verified official podium for a known marathon or half marathon. "
            + "Without the year, look for the latest confirmed finished edition. This tool reads only the local cache.")
    public PodiumResult podium(
            @ToolParam(description = "Official race name or known alias") String race_name,
            @ToolParam(required = false, description = "Race edition year") Integer year,
            @ToolParam(required = false, description = "All, Men or Women, default All") String category,
            @ToolParam(required = false, description = "MARATHON or HALF_MARATHON") String distance,
            ToolContext context) {
        return execute(PODIUM, context, () -> service.podium(race_name, year, category, distance, false, false));
    }

    private static <T> T execute(String tool, ToolContext context, Supplier<T> action) {
        if (Thread.currentThread().isInterrupted()) {
            throw new AiRequestException("Agent execution cancelled", null);
        }
        Object value = context == null ? null : context.getContext().get(AgentExecutionTrace.CONTEXT_KEY);
        AgentExecutionTrace trace = value instanceof AgentExecutionTrace execution ? execution : null;
        int step = trace == null ? 0 : trace.nextToolCall();
        long started = System.nanoTime();
        String status = "FAILED";
        try {
            T result = action.get();
            status = "SUCCESS";
            return result;
        } finally {
            log.info("agentExecutionId={} step={} tool={} status={} latencyMs={}",
                    trace == null ? "none" : trace.executionId(), step, tool, status,
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
    }
}
