package cn.watsonzhu.runagent.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.watsonzhu.runagent.config.RunningDataProperties;
import cn.watsonzhu.runagent.exception.RunningDataUnavailableException;
import cn.watsonzhu.runagent.model.running.RunningActivity;
import cn.watsonzhu.runagent.model.running.RunningSummary;
import cn.watsonzhu.runagent.model.running.RunningActivitySummary;
import cn.watsonzhu.runagent.model.running.DistanceType;
import cn.watsonzhu.runagent.model.running.PersonalBest;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 每次查询读取一次生成后的 JSON，文件更新即可生效；数据量较小时无需引入缓存或数据库。
 * 只映射统计所需字段；Tool 返回精简结果，不把轨迹放入模型上下文。
 */
@Service
public class RunningDataService {

    private static final Pattern DURATION = Pattern.compile("(?:(\\d+) days?, )?(\\d+):([0-5]\\d):([0-5]\\d)");
    public static final double PERSONAL_BEST_DISTANCE_TOLERANCE = 0.05;

    private final RunningDataProperties properties;
    private final ObjectMapper objectMapper;

    public RunningDataService(RunningDataProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public RunningSummary summarize(LocalDate start, LocalDate end) {
        try {
            int count = 0;
            BigDecimal distanceMeters = BigDecimal.ZERO;
            long durationSeconds = 0;
            for (RunningActivity activity : loadRuns()) {
                LocalDate date = parseLocalDate(activity.startDateLocal());
                if (date.isBefore(start) || date.isAfter(end)) {
                    continue;
                }
                validateDistance(activity.distance());
                count++;
                distanceMeters = distanceMeters.add(BigDecimal.valueOf(activity.distance()));
                durationSeconds = Math.addExact(durationSeconds, parseDurationSeconds(activity.movingTime()));
            }
            return new RunningSummary(start, end, count,
                    distanceMeters.movePointLeft(3).doubleValue(), durationSeconds);
        } catch (RunningDataUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    public List<RunningActivitySummary> recentRuns(LocalDate start, LocalDate end, int limit) {
        try {
            return loadRuns().stream()
                    .filter(activity -> {
                        LocalDate date = parseLocalDate(activity.startDateLocal());
                        return !date.isBefore(start) && !date.isAfter(end);
                    })
                    .sorted(Comparator.comparing(RunningActivity::startDateLocal).reversed())
                    .limit(limit)
                    .map(this::toSummary)
                    .toList();
        } catch (RunningDataUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    public PersonalBest personalBest(DistanceType type) {
        try {
            double target = type.meters();
            Optional<RunningActivity> best = loadRuns().stream()
                    .filter(activity -> {
                        validateDistance(activity.distance());
                        return activity.distance() > 0
                                && Math.abs(activity.distance() - target) <= target * PERSONAL_BEST_DISTANCE_TOLERANCE;
                    })
                    .min(Comparator.comparingDouble(activity ->
                            parseDurationSeconds(activity.movingTime()) / activity.distance()));
            if (best.isEmpty()) {
                return new PersonalBest(type, null, 0, 0, null, true, "NO_MATCHING_ACTIVITY");
            }
            RunningActivitySummary summary = toSummary(best.orElseThrow());
            return new PersonalBest(type, summary.date(), summary.distanceKm(), summary.durationSeconds(),
                    summary.averagePaceSecondsPerKm(), true, "WHOLE_ACTIVITY_DISTANCE_MATCH");
        } catch (RunningDataUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private RunningActivitySummary toSummary(RunningActivity activity) {
        validateDistance(activity.distance());
        if (activity.runId() == null) {
            throw unavailable();
        }
        long seconds = parseDurationSeconds(activity.movingTime());
        double km = activity.distance() / 1_000;
        Integer pace = km > 0 ? Math.toIntExact(Math.round(seconds / km)) : null;
        return new RunningActivitySummary(activity.runId(), parseLocalDate(activity.startDateLocal()), km, seconds, pace);
    }

    private static void validateDistance(Double distance) {
        if (distance == null || !Double.isFinite(distance) || distance < 0) {
            throw unavailable();
        }
    }

    private List<RunningActivity> loadRuns() {
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
            return activities.stream().filter(activity -> "Run".equals(activity.type())).toList();
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
