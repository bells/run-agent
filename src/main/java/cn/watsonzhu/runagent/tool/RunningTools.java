package cn.watsonzhu.runagent.tool;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

import cn.watsonzhu.runagent.agent.AgentExecutionTrace;
import cn.watsonzhu.runagent.exception.InvalidRunningQueryException;
import cn.watsonzhu.runagent.exception.RunningDataUnavailableException;
import cn.watsonzhu.runagent.model.running.DistanceType;
import cn.watsonzhu.runagent.model.running.PersonalBest;
import cn.watsonzhu.runagent.model.running.RunningActivitySummary;
import cn.watsonzhu.runagent.model.running.RunningSummary;
import cn.watsonzhu.runagent.service.RunningDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class RunningTools {
    private static final Logger log = LoggerFactory.getLogger(RunningTools.class);
    private static final long MAX_QUERY_DAYS = 366;
    private final RunningDataService runningDataService;

    public RunningTools(RunningDataService runningDataService) {
        this.runningDataService = runningDataService;
    }

    @Tool(name = "getRunningSummary", description = "Get the user's real historical running statistics "
            + "for an inclusive date range. Use for own run count, total distance or duration. "
            + "Call separately for each period in a comparison. Do not use for general knowledge.")
    public RunningSummary getRunningSummary(
            @ToolParam(description = "Inclusive start date in ISO-8601 yyyy-MM-dd format") String startDate,
            @ToolParam(description = "Inclusive end date in ISO-8601 yyyy-MM-dd format") String endDate,
            ToolContext context) {
        return execute("getRunningSummary", context, () -> {
            DateRange range = parseRange(startDate, endDate);
            return runningDataService.summarize(range.start(), range.end());
        });
    }

    // Retain the v0.2 Java signature; only the annotated method is exposed as a Tool.
    public RunningSummary getRunningSummary(String startDate, String endDate) {
        return getRunningSummary(startDate, endDate, null);
    }

    @Tool(name = "getRecentRuns", description = "Get recent individual running activities in an inclusive date range. "
            + "Use for individual workouts, pace, distance or recent frequency. Most recent first.")
    public List<RunningActivitySummary> getRecentRuns(
            @ToolParam(description = "Inclusive start date in ISO-8601 yyyy-MM-dd format") String startDate,
            @ToolParam(description = "Inclusive end date in ISO-8601 yyyy-MM-dd format") String endDate,
            @ToolParam(description = "Maximum number of runs, from 1 to 20") int limit,
            ToolContext context) {
        return execute("getRecentRuns", context, () -> {
            DateRange range = parseRange(startDate, endDate);
            if (limit < 1 || limit > 20) {
                throw new InvalidRunningQueryException("limit must be between 1 and 20");
            }
            return runningDataService.recentRuns(range.start(), range.end(), limit);
        });
    }

    @Tool(name = "getPersonalBest", description = "Find the fastest average-pace whole running activity near "
            + "a standard distance. This is an approximate activity-level result, never an exact split or race PB.")
    public PersonalBest getPersonalBest(
            @ToolParam(description = "Standard distance: FIVE_K, TEN_K, HALF_MARATHON or MARATHON") String distanceType,
            ToolContext context) {
        return execute("getPersonalBest", context, () -> {
            DistanceType type;
            try {
                type = DistanceType.valueOf(distanceType);
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new InvalidRunningQueryException("distanceType must be FIVE_K, TEN_K, HALF_MARATHON or MARATHON");
            }
            return runningDataService.personalBest(type);
        });
    }

    private static DateRange parseRange(String startDate, String endDate) {
        LocalDate start = parseDate(startDate, "startDate");
        LocalDate end = parseDate(endDate, "endDate");
        if (start.isAfter(end)) {
            throw new InvalidRunningQueryException("startDate must not be after endDate");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_QUERY_DAYS) {
            throw new InvalidRunningQueryException("Date range must not exceed 366 inclusive days");
        }
        return new DateRange(start, end);
    }

    private static LocalDate parseDate(String value, String parameter) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new InvalidRunningQueryException(parameter + " must be an ISO-8601 yyyy-MM-dd date");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new InvalidRunningQueryException(parameter + " must be a valid ISO-8601 yyyy-MM-dd date");
        }
    }

    private static <T> T execute(String name, ToolContext context, Supplier<T> action) {
        AgentExecutionTrace trace = context == null ? null
                : (AgentExecutionTrace) context.getContext().get(AgentExecutionTrace.CONTEXT_KEY);
        String executionId = trace == null ? "none" : trace.executionId();
        String conversationId = trace == null ? "none" : trace.conversationId();
        int step = trace == null ? 0 : trace.nextToolCall();
        long startedAt = System.nanoTime();
        log.info("conversationId={} agentExecutionId={} step={} tool={} phase=SELECTED", conversationId, executionId, step, name);
        try {
            log.info("conversationId={} agentExecutionId={} step={} tool={} phase=EXECUTING", conversationId, executionId, step, name);
            T result = action.get();
            log.info("conversationId={} agentExecutionId={} step={} tool={} status=SUCCESS latencyMs={}",
                    conversationId, executionId, step, name, elapsedMillis(startedAt));
            return result;
        } catch (InvalidRunningQueryException | RunningDataUnavailableException exception) {
            log.warn("conversationId={} agentExecutionId={} step={} tool={} status=FAILED reason={} latencyMs={}",
                    conversationId, executionId, step, name, exception.getClass().getSimpleName(), elapsedMillis(startedAt));
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("conversationId={} agentExecutionId={} step={} tool={} status=FAILED reason=Unexpected latencyMs={}",
                    conversationId, executionId, step, name, elapsedMillis(startedAt));
            throw new RunningDataUnavailableException("Running data could not be retrieved");
        }
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private record DateRange(LocalDate start, LocalDate end) {
    }
}
