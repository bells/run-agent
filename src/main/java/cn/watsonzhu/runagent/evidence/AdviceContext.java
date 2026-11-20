package cn.watsonzhu.runagent.evidence;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

public record AdviceContext(String city, LocalDateTime runningAt, String goal, RelativeTime relativeTime) {
    public static final AdviceContext NONE = new AdviceContext(null, null);
    private static final Set<String> FIELDS = Set.of("city", "runningAt", "goal");
    private static final Pattern CHAT_CITY = Pattern.compile(
            "(?:我(?:现在)?在|我住在|地点[是为])([\\p{IsHan}A-Za-z· ]{1,30})(?=[，,。；;！？!?]|$)");
    private static final Pattern CHAT_TIME = Pattern.compile(
            "(今天|今晚|今早|明天|明晚|明早|后天)\\s*(凌晨|早上|上午|中午|下午|傍晚|晚上)?\\s*"
                    + "(\\d{1,2}|[零一二两三四五六七八九十]{1,3})(?:[:：]([0-5]\\d)|点(半)?)");

    public AdviceContext(String city, LocalDateTime runningAt) {
        this(city, runningAt, null);
    }

    public AdviceContext(String city, LocalDateTime runningAt, String goal) {
        this(city, runningAt, goal, null);
    }

    public static AdviceContext parse(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return NONE;
        }
        if (!node.isObject() || node.properties().stream().anyMatch(field -> !FIELDS.contains(field.getKey()))) {
            throw new IllegalArgumentException("Invalid advice context");
        }
        String city = text(node, "city", 80);
        String time = text(node, "runningAt", 25);
        String goal = text(node, "goal", 300);
        try {
            return new AdviceContext(city, time == null ? null : LocalDateTime.parse(time), goal);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Enter a local date and time");
        }
    }

    public AdviceContext withChatHints(String message) {
        var matcher = CHAT_CITY.matcher(message);
        String resolvedCity = matcher.find() ? matcher.group(1).strip() : city;
        return new AdviceContext(resolvedCity, runningAt, goal,
                runningAt == null ? relativeTime(message) : null);
    }

    LocalDateTime resolveTime(LocalDateTime now) {
        if (runningAt != null) return runningAt;
        if (relativeTime == null) return now;
        return now.toLocalDate().plusDays(relativeTime.days()).atTime(relativeTime.time());
    }

    private static RelativeTime relativeTime(String message) {
        var match = CHAT_TIME.matcher(message);
        if (!match.find()) return null;
        String day = match.group(1);
        String period = match.group(2) == null ? day : match.group(2);
        int hour = hour(match.group(3));
        if (hour < 0 || hour > 23) return null;
        if (hour < 12 && (period.contains("晚") || period.equals("下午"))) hour += 12;
        int minute = match.group(4) != null ? Integer.parseInt(match.group(4)) : match.group(5) != null ? 30 : 0;
        int days = day.startsWith("明") ? 1 : day.startsWith("后") ? 2 : 0;
        return new RelativeTime(days, LocalTime.of(hour, minute));
    }

    private static int hour(String text) {
        if (text.charAt(0) >= '0' && text.charAt(0) <= '9') return Integer.parseInt(text);
        String digits = "零一二三四五六七八九";
        String normalized = text.replace('两', '二');
        if (normalized.length() == 1) return normalized.equals("十") ? 10 : digits.indexOf(normalized);
        int tens = normalized.indexOf('十');
        if (tens < 0 || tens > 1 || normalized.length() - tens > 2) return -1;
        int tail = normalized.length() == tens + 1 ? 0 : digits.indexOf(normalized.charAt(tens + 1));
        if (tail < 0) return -1;
        return (tens == 0 ? 1 : digits.indexOf(normalized.charAt(0))) * 10 + tail;
    }

    private static String text(JsonNode node, String field, int limit) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isString() || value.asString().isBlank()
                || value.asString().codePointCount(0, value.asString().length()) > limit
                || value.asString().codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid advice context");
        }
        return value.asString().strip();
    }

    public record RelativeTime(int days, LocalTime time) { }
}
