package cn.watsonzhu.runagent.tool;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

import cn.watsonzhu.runagent.exception.InvalidRunningQueryException;
import cn.watsonzhu.runagent.exception.RunningDataUnavailableException;
import cn.watsonzhu.runagent.model.running.RunningSummary;
import cn.watsonzhu.runagent.service.RunningDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring AI 从 @Tool / @ToolParam 生成给模型看的工具定义；模型选中后，才由应用执行此方法。
 * 这里是参数和安全边界，文件读取与统计交给 RunningDataService。
 */
@Component
public class RunningTools {

    private static final Logger log = LoggerFactory.getLogger(RunningTools.class);
    // 366 天允许完整闰年，同时阻止模型一次提出跨越多年的查询。
    private static final long MAX_QUERY_DAYS = 366;

    private final RunningDataService runningDataService;

    public RunningTools(RunningDataService runningDataService) {
        this.runningDataService = runningDataService;
    }

    @Tool(name = "getRunningSummary", description = "Get the user's real historical running statistics "
            + "for an inclusive date range. Use for questions about the user's own run count, total distance "
            + "or total duration. Do not use for general running knowledge, training advice or definitions.")
    public RunningSummary getRunningSummary(
            @ToolParam(description = "Inclusive start date in ISO-8601 yyyy-MM-dd format") String startDate,
            @ToolParam(description = "Inclusive end date in ISO-8601 yyyy-MM-dd format") String endDate) {
        long startedAt = System.nanoTime();
        log.info("tool=getRunningSummary phase=SELECTED");
        try {
            // JSON Schema 主要引导模型生成参数，不能代替 Java 对不可信输入的实际校验。
            LocalDate start = parseDate(startDate, "startDate");
            LocalDate end = parseDate(endDate, "endDate");
            if (start.isAfter(end)) {
                throw new InvalidRunningQueryException("startDate must not be after endDate");
            }
            if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_QUERY_DAYS) {
                throw new InvalidRunningQueryException("Date range must not exceed 366 inclusive days");
            }
            log.info("tool=getRunningSummary phase=EXECUTING startDate={} endDate={}", start, end);
            RunningSummary summary = runningDataService.summarize(start, end);
            log.info("tool=getRunningSummary status=SUCCESS runCount={} totalDistanceKm={} latencyMs={}",
                    summary.runCount(), summary.totalDistanceKm(), elapsedMillis(startedAt));
            return summary;
        } catch (InvalidRunningQueryException | RunningDataUnavailableException exception) {
            log.warn("tool=getRunningSummary status=FAILED reason={} latencyMs={}",
                    exception.getClass().getSimpleName(), elapsedMillis(startedAt));
            throw exception;
        } catch (RuntimeException exception) {
            // 工具错误可能作为 Tool Result 再交给模型；统一错误文本，避免泄漏文件路径或解析细节。
            log.warn("tool=getRunningSummary status=FAILED reason=Unexpected latencyMs={}", elapsedMillis(startedAt));
            throw new RunningDataUnavailableException("Running data could not be retrieved");
        }
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

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
