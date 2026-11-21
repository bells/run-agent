package cn.watsonzhu.runagent.marathon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import static cn.watsonzhu.runagent.marathon.MarathonData.*;

public final class MarathonCalendar {

    private static final Logger log = LoggerFactory.getLogger(MarathonCalendar.class);
    private static final ZoneId DOMESTIC_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 30;
    private static final Set<String> FEATURED_TAGS = Set.of("major", "world_athletics_label", "domestic_featured",
            "international_featured");

    private final ObjectReader reader;
    private final Path snapshot;
    private final Clock clock;
    private CalendarSnapshot current;
    private BasicFileAttributes lastSeen;
    private boolean fileMissing;

    public MarathonCalendar(ObjectMapper json, Path snapshot, Clock clock) {
        this.reader = json.readerFor(CalendarSnapshot.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.snapshot = snapshot;
        this.clock = clock;
        try (var seed = getClass().getResourceAsStream("/marathon/marathon_events.json")) {
            this.current = read(seed);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Invalid bundled marathon calendar", exception);
        }
    }

    public synchronized CalendarSnapshot current() {
        if (snapshot == null) return current;
        try {
            var attributes = Files.readAttributes(snapshot, BasicFileAttributes.class);
            if (lastSeen != null && attributes.size() == lastSeen.size()
                    && attributes.lastModifiedTime().equals(lastSeen.lastModifiedTime())
                    && java.util.Objects.equals(attributes.fileKey(), lastSeen.fileKey())) {
                return current;
            }
            lastSeen = attributes;
            fileMissing = false;
            if (!attributes.isRegularFile() || attributes.size() > MAX_BYTES) {
                log.warn("Ignoring unreadable marathon calendar snapshot");
                return current;
            }
            try (var input = Files.newInputStream(snapshot)) {
                current = read(input);
            }
        } catch (java.nio.file.NoSuchFileException exception) {
            lastSeen = null;
            if (!fileMissing) log.debug("Marathon calendar snapshot not present");
            fileMissing = true;
        } catch (IOException | RuntimeException exception) {
            log.warn("Ignoring invalid marathon calendar snapshot");
        }
        return current;
    }

    public CalendarResult query(String city, String month, String distance, Integer limit, String raceName,
                                boolean featured) {
        CalendarSnapshot data = current();
        if (limit != null && limit < 1 || tooLong(city, 80) || tooLong(raceName, 160)
                || tooLong(distance, 40) || tooLong(month, 7)) {
            return result(Status.INVALID, List.of(), data);
        }

        YearMonth selectedMonth;
        Distance selectedDistance;
        try {
            selectedMonth = blank(month) ? null : parseMonth(month.strip());
            selectedDistance = blank(distance) ? null : Distance.parse(distance);
        } catch (DateTimeException | IllegalArgumentException exception) {
            return result(Status.INVALID, List.of(), data);
        }

        int maxResults = limit == null ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        String locality = normalizePlace(city);
        var matches = matchSeries(data.series(), raceName);
        var bySeriesId = matches.stream().filter(series -> !featured
                        || series.tags().stream().anyMatch(FEATURED_TAGS::contains))
                .collect(Collectors.toMap(Series::seriesId, Function.identity()));

        Instant now = clock.instant();
        var entries = data.events().stream()
                .filter(event -> bySeriesId.containsKey(event.seriesId()))
                .filter(event -> selectedDistance == null || event.distances().contains(selectedDistance))
                .filter(event -> locality.isEmpty() || locality.equals(normalizePlace(event.city()))
                        || locality.equals(normalizePlace(event.province())))
                .filter(event -> inWindow(event, selectedMonth, now))
                .sorted(Comparator.comparing(Event::raceDate).thenComparing(Event::eventId))
                .map(event -> entry(event, bySeriesId.get(event.seriesId()), now))
                .toList();
        LocalDate today = LocalDate.ofInstant(now, DOMESTIC_ZONE);
        LocalDate through = windowEnd(today, selectedMonth);
        boolean overlapsWindow = !through.isBefore(today)
                && (selectedMonth == null || !selectedMonth.atDay(1).isAfter(through));
        Status status = entries.isEmpty() ? Status.NO_MATCH : Status.READY;
        if (!blank(raceName) && entries.stream().map(entry -> entry.event().seriesId()).distinct().count() > 1) {
            status = Status.AMBIGUOUS;
        } else if (overlapsWindow && data.coverageThrough().isBefore(through)) {
            status = Status.PARTIAL;
        }
        return result(status, entries.stream().limit(maxResults).toList(), data);
    }

    private static boolean inWindow(Event event, YearMonth month, Instant now) {
        if (event.status() != EventStatus.CONFIRMED && event.status() != EventStatus.PLANNED) return false;
        LocalDate today = LocalDate.ofInstant(now, ZoneId.of(event.timezone()));
        return !event.raceDate().isBefore(today) && !event.raceDate().isAfter(windowEnd(today, month))
                && (month == null || YearMonth.from(event.raceDate()).equals(month));
    }

    private static LocalDate windowEnd(LocalDate today, YearMonth month) {
        LocalDate end = today.plusMonths(3).minusDays(1);
        return month != null && month.atEndOfMonth().isBefore(end) ? month.atEndOfMonth() : end;
    }

    private static CalendarEntry entry(Event event, Series series, Instant now) {
        LocalDate localToday = LocalDate.ofInstant(now, ZoneId.of(event.timezone()));
        Long untilDeadline = event.regDeadline() == null ? null : ChronoUnit.DAYS.between(localToday, event.regDeadline());
        return new CalendarEntry(event, series.name(), series.aliases(),
                ChronoUnit.DAYS.between(localToday, event.raceDate()), untilDeadline);
    }

    static List<Series> matchSeries(List<Series> series, String query) {
        if (blank(query)) return series;
        String name = normalizeName(query);
        if (name.isEmpty()) return List.of();
        var exact = series.stream().filter(item -> normalizeName(item.name()).equals(name)
                || item.aliases().stream().map(MarathonCalendar::normalizeName).anyMatch(name::equals)).toList();
        if (!exact.isEmpty() || name.length() < 2) return exact;
        return series.stream().filter(item -> normalizeName(item.name()).startsWith(name)
                        || item.aliases().stream().map(MarathonCalendar::normalizeName).anyMatch(alias -> alias.startsWith(name)))
                .toList();
    }

    private CalendarSnapshot read(InputStream input) throws IOException {
        if (input == null) throw new IOException("Marathon calendar snapshot missing");
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IOException("Marathon calendar snapshot too large");
        var data = (CalendarSnapshot) reader.readValue(bytes);
        if (data == null) throw new IOException("Marathon calendar snapshot is empty");
        return data;
    }

    private static CalendarResult result(Status status, List<CalendarEntry> entries, CalendarSnapshot data) {
        return new CalendarResult(status, entries, data.publishedAt(), data.coverageThrough());
    }

    private static String normalizeName(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\p{Z}\\p{P}\\s]+", "");
    }

    private static String normalizePlace(String value) {
        return value == null ? "" : normalizeName(value).replaceFirst("(省|市|维吾尔自治区|回族自治区|壮族自治区|自治区|特别行政区)$", "");
    }

    private static YearMonth parseMonth(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException("Invalid calendar month");
        return YearMonth.parse(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean tooLong(String value, int limit) {
        return value != null && value.strip().length() > limit;
    }
}
