package cn.watsonzhu.runagent.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.watsonzhu.runagent.config.RunningDataProperties;
import cn.watsonzhu.runagent.exception.RunningDataUnavailableException;
import cn.watsonzhu.runagent.model.running.RunningActivity;
import cn.watsonzhu.runagent.model.running.RunningSummary;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 每次查询读取一次生成后的 JSON，文件更新即可生效；数据量较小时无需引入缓存或数据库。
 * 只向 Tool 返回汇总值，活动明细和轨迹不进入模型上下文。
 */
@Service
public class RunningDataService {

    private static final Pattern DURATION = Pattern.compile("(?:(\\d+) days?, )?(\\d+):([0-5]\\d):([0-5]\\d)");

    private final RunningDataProperties properties;
    private final ObjectMapper objectMapper;

    public RunningDataService(RunningDataProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public RunningSummary summarize(LocalDate start, LocalDate end) {
        String configuredPath = properties.path();
        if (configuredPath == null || configuredPath.isBlank()) {
            throw unavailable();
        }
        try {
            Path path = Path.of(configuredPath);
            if (!Files.isRegularFile(path) || !Files.isReadable(path) || Files.size(path) == 0) {
                throw unavailable();
            }
            // 用最小的 RunningActivity 类型映射所需字段；JSON 中其他字段不会被返回或记录。
            List<RunningActivity> activities = objectMapper.readValue(
                    Files.readAllBytes(path), new TypeReference<List<RunningActivity>>() {});
            if (activities == null) {
                throw unavailable();
            }
            int count = 0;
            BigDecimal distanceMeters = BigDecimal.ZERO;
            long durationSeconds = 0;
            for (RunningActivity activity : activities) {
                // 先筛选运动类型，再按本地日期过滤；查询区间两端都包含。
                if (!"Run".equals(activity.type())) {
                    continue;
                }
                LocalDate date = parseLocalDate(activity.startDateLocal());
                if (date.isBefore(start) || date.isAfter(end)) {
                    continue;
                }
                if (activity.distance() == null || !Double.isFinite(activity.distance())
                        || activity.distance() < 0) {
                    throw unavailable();
                }
                count++;
                // 源文件单位是米；用 BigDecimal 累加十进制数，最后才转换为公里。
                distanceMeters = distanceMeters.add(BigDecimal.valueOf(activity.distance()));
                durationSeconds = Math.addExact(durationSeconds, parseDurationSeconds(activity.movingTime()));
            }
            return new RunningSummary(start, end, count,
                    distanceMeters.movePointLeft(3).doubleValue(), durationSeconds);
        } catch (RunningDataUnavailableException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw unavailable();
        }
    }

    // 兼容 H:MM:SS 和生成数据可能出现的 “N days, H:MM:SS”，统一转换为秒。
    static long parseDurationSeconds(String value) {
        if (value == null) {
            throw unavailable();
        }
        Matcher matcher = DURATION.matcher(value);
        if (!matcher.matches()) {
            throw unavailable();
        }
        try {
            long days = matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1));
            long hours = Long.parseLong(matcher.group(2));
            long minutes = Long.parseLong(matcher.group(3));
            long seconds = Long.parseLong(matcher.group(4));
            return Math.addExact(Math.multiplyExact(days, 86_400),
                    Math.addExact(Math.multiplyExact(hours, 3_600), minutes * 60 + seconds));
        } catch (ArithmeticException | NumberFormatException exception) {
            throw unavailable();
        }
    }

    private static LocalDate parseLocalDate(String value) {
        // 按 start_date_local 的日历日期统计，避免转换 UTC 后把跨午夜的跑步算到另一天。
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}(?:[ T].*)")) {
            throw unavailable();
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException exception) {
            throw unavailable();
        }
    }

    private static RunningDataUnavailableException unavailable() {
        return new RunningDataUnavailableException("Running data could not be retrieved");
    }
}
