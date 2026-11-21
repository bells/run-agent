package cn.watsonzhu.runagent.marathon;

import java.net.URI;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

public final class MarathonData {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9_-]{0,79}");
    private static final Pattern HOST = Pattern.compile("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?");
    private static final Pattern FINISH_TIME = Pattern.compile("[0-9]{2}:[0-5][0-9]:[0-5][0-9]");

    private MarathonData() {}

    public enum Distance {
        MARATHON, HALF_MARATHON;

        public static Distance parse(String value) {
            return switch (Normalizer.normalize(value.strip(), Normalizer.Form.NFKC)
                    .toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_')) {
                case "marathon", "full", "full_marathon", "全马", "全程", "全程马拉松", "42.195", "42.195km" -> MARATHON;
                case "half_marathon", "half", "半马", "半程", "半程马拉松", "21.0975", "21.0975km" -> HALF_MARATHON;
                default -> throw new IllegalArgumentException("Unknown marathon distance");
            };
        }
    }

    public enum EventStatus { PLANNED, CONFIRMED, POSTPONED, CANCELLED, COMPLETED }
    public enum Category { MEN, WOMEN }
    public enum SubCategory { OVERALL, DOMESTIC }
    public enum Status { READY, PARTIAL, NO_MATCH, AMBIGUOUS, INVALID, NOT_STARTED, PENDING, NOT_CACHED, UNAVAILABLE }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Series(String seriesId, String name, List<String> aliases, List<String> tags,
                         List<String> officialHosts) {
        public Series {
            requireSlug(seriesId);
            requireText(name, 160);
            aliases = texts(aliases, 40, 160);
            tags = texts(tags, 20, 80);
            officialHosts = texts(officialHosts, 30, 253);
            if (officialHosts.stream().anyMatch(host -> !HOST.matcher(host).matches() || host.contains(".."))) {
                throw new IllegalArgumentException("Invalid official marathon host");
            }
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Event(String eventId, String seriesId, int year, LocalDate raceDate, String timezone,
                        String city, String province, String country, List<Distance> distances,
                        Map<Distance, LocalTime> startTimes, LocalDate regDeadline,
                        String certLevel, EventStatus status, Source source) {
        public Event {
            requireEdition(eventId, seriesId, year);
            requireText(timezone, 80);
            ZoneId.of(timezone);
            requireText(city, 80);
            requireOptionalText(province, 80);
            requireText(country, 80);
            requireOptionalText(certLevel, 80);
            if (raceDate == null || status == null || source == null || distances == null || distances.isEmpty()) {
                throw new IllegalArgumentException("Incomplete marathon edition");
            }
            distances = List.copyOf(distances);
            startTimes = startTimes == null ? Map.of() : Map.copyOf(startTimes);
            if (new HashSet<>(distances).size() != distances.size() || !distances.containsAll(startTimes.keySet())
                    || regDeadline != null && regDeadline.isAfter(raceDate)) {
                throw new IllegalArgumentException("Invalid marathon schedule");
            }
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Source(String url, String organization, Instant verifiedAt) {
        public Source {
            requireText(url, 2048);
            requireText(organization, 160);
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 && uri.getPort() != 443 || verifiedAt == null) {
                throw new IllegalArgumentException("Invalid marathon source");
            }
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CalendarSnapshot(int schemaVersion, Instant publishedAt, LocalDate coverageThrough,
                                   List<Series> series, List<Event> events) {
        public CalendarSnapshot {
            if (schemaVersion != 1 || publishedAt == null || coverageThrough == null || series == null
                    || series.isEmpty() || series.size() > 1000 || events == null || events.isEmpty()
                    || events.size() > 10000) {
                throw new IllegalArgumentException("Invalid marathon calendar snapshot");
            }
            series = List.copyOf(series);
            events = List.copyOf(events);
            var seriesIds = new HashSet<String>();
            for (var item : series) {
                if (!seriesIds.add(item.seriesId())) throw new IllegalArgumentException("Duplicate marathon series");
            }
            var eventIds = new HashSet<String>();
            for (var event : events) {
                if (!seriesIds.contains(event.seriesId()) || !eventIds.add(event.eventId())) {
                    throw new IllegalArgumentException("Invalid or duplicate marathon edition");
                }
            }
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CalendarResult(Status status, List<CalendarEntry> events, Instant updatedAt,
                                 LocalDate coverageThrough) {
        public CalendarResult {
            events = List.copyOf(events);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CalendarEntry(Event event, String name, List<String> aliases, long daysUntilRace,
                                Long daysUntilRegistrationDeadline) {
        public CalendarEntry {
            aliases = List.copyOf(aliases);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Podium(int schemaVersion, String eventId, String seriesId, int year, LocalDate raceDate,
                         Distance distance, List<Group> groups, String highlights, Instant updatedAt) {
        public Podium {
            requireEdition(eventId, seriesId, year);
            requireOptionalText(highlights, 600);
            if (schemaVersion != 1 || raceDate == null || distance == null || groups == null || groups.isEmpty()
                    || groups.size() > 4 || updatedAt == null) {
                throw new IllegalArgumentException("Invalid marathon podium");
            }
            groups = List.copyOf(groups);
            if (groups.stream().map(group -> Map.entry(group.category(), group.subCategory())).distinct().count()
                    != groups.size()) {
                throw new IllegalArgumentException("Duplicate marathon podium group");
            }
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Group(Category category, SubCategory subCategory, Source source, List<Rank> results,
                        List<Withdrawal> withdrawals) {
        public Group(Category category, SubCategory subCategory, Source source, List<Rank> results) {
            this(category, subCategory, source, results, List.of());
        }

        public Group {
            withdrawals = withdrawals == null ? List.of() : List.copyOf(withdrawals);
            if (category == null || subCategory == null || source == null || results == null
                    || results.isEmpty() && withdrawals.isEmpty() || results.size() > 12 || withdrawals.size() > 24) {
                throw new IllegalArgumentException("Invalid marathon podium group");
            }
            results = List.copyOf(results);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Withdrawal(String athleteName, Source source) {
        public Withdrawal {
            requireText(athleteName, 160);
            if (source == null) throw new IllegalArgumentException("Missing withdrawal source");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Rank(int rank, String athleteName, String nationality, String finishTime, Boolean isCourseRecord) {
        public Rank {
            requireText(athleteName, 160);
            requireOptionalText(nationality, 80);
            if (rank < 1 || rank > 3 || finishTime == null || !FINISH_TIME.matcher(finishTime).matches()
                    || "00:00:00".equals(finishTime)) {
                throw new IllegalArgumentException("Invalid marathon rank or finish time");
            }
        }
    }

    private static List<String> texts(List<String> entries, int maxEntries, int maxLength) {
        if (entries == null) return List.of();
        if (entries.size() > maxEntries) throw new IllegalArgumentException("Too many marathon metadata entries");
        entries.forEach(value -> requireText(value, maxLength));
        return List.copyOf(entries);
    }

    private static void requireEdition(String eventId, String seriesId, int year) {
        requireSlug(seriesId);
        if (year < 1900 || year > 9999 || !(seriesId + "_" + year).equals(eventId)) {
            throw new IllegalArgumentException("Invalid marathon edition key");
        }
    }

    private static void requireSlug(String value) {
        if (value == null || !SLUG.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid marathon series key");
        }
    }

    private static void requireText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid marathon metadata");
        }
    }

    private static void requireOptionalText(String value, int maxLength) {
        if (value != null) requireText(value, maxLength);
    }
}
