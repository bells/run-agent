package cn.watsonzhu.runagent.marathon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Reads race-level aggregates published by the Python collector. */
public final class MarathonSub3 {
    private static final Logger log = LoggerFactory.getLogger(MarathonSub3.class);
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final String SCOPE = "CN_MAINLAND_CAA_AB_MARATHON";
    private static final String OFFICIAL_EXACT_COUNT = "OFFICIAL_EXACT_COUNT";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Pattern EVENT_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,79}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Comparator<Event> BY_COUNT = Comparator.comparing(Event::verifiedCount)
            .reversed().thenComparing(Event::eventId);

    private final ObjectReader reader;
    private final Path snapshotPath;
    private final Clock clock;

    public MarathonSub3(ObjectMapper json, Path snapshotPath, Clock clock) {
        this.reader = json.readerFor(Snapshot.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.snapshotPath = snapshotPath;
        this.clock = clock;
    }

    public Query query(Integer requestedYear, String race, Integer limit) {
        int year = requestedYear == null ? LocalDate.now(clock.withZone(CHINA)).getYear() : requestedYear;
        return selection(read(year), year, race, limit);
    }

    String answer(int year, String message, boolean leaderboard) {
        Snapshot snapshot = read(year);
        if (snapshot == null) return year + " 年的马拉松破三统计暂不可用。";
        if (!leaderboard) {
            String compact = compact(message);
            var matches = snapshot.events().stream().filter(row -> names(row).stream().anyMatch(compact::contains))
                    .toList();
            if (matches.isEmpty()) return year + " 年快照中没有匹配赛事的破三数据，请核对赛事全称和届次。";
            if (matches.size() > 1) return "请指定一场赛事，或查询年度已核验破三榜。";
            return describe(matches.getFirst(), year);
        }
        Query result = selection(snapshot, year, null, 10);
        StringBuilder answer = new StringBuilder();
        answer.append(year).append(" 年中国大陆田协 A/B 类全马破三人数 · 已核验赛事\n")
                .append("口径：有效净成绩 < 03:00:00。统计截至 ").append(result.asOf())
                .append("，已核验 ").append(result.verifiedCount()).append(" 场，快照收录 ")
                .append(result.cataloguedCount()).append(" 场候选赛事。");
        if (!result.catalogComplete()) answer.append(" 年度候选目录仍在补录。");
        if (result.entries().isEmpty()) {
            answer.append("\n目前没有满足口径的已核验赛事，无法排名。");
        }
        for (var ranked : result.entries()) {
            Event event = ranked.event();
            answer.append("\n").append(ranked.rank()).append(". ").append(event.name())
                    .append(" · ").append(event.verifiedCount()).append(" 人");
            if (event.stale()) answer.append("（上次核验）");
            answer.append(" · ").append(event.sourceUrl());
        }
        if (result.cutoffTie()) answer.append("\n榜尾还有同人数赛事。");
        if (!result.disclosed().isEmpty()) answer.append("\n另有口径待核实的官方披露，可按赛事名称查询。");
        return answer.toString();
    }

    private static String describe(Event row, int year) {
        String label = year + " 年" + row.name();
        String stale = row.stale() ? "（上次采集）" : "";
        return switch (row.status()) {
            case VERIFIED -> label + "有效净成绩严格小于 3 小时的完赛者有 " + row.verifiedCount() + " 人"
                    + stale + "。来源：" + row.sourceUrl();
            case DISCLOSED -> label + "官方披露破三人数 " + row.disclosedCount() + " 人" + stale
                    + "。计时或三小时边界口径待核实，未进入严格榜。来源：" + row.sourceUrl();
            case PENDING, UNAVAILABLE -> label + "的破三人数暂未核验。赛事来源：" + row.editionSource();
        };
    }

    private static Query selection(Snapshot snapshot, int year, String race, Integer requestedLimit) {
        if (snapshot == null) return unavailable(year);
        boolean specific = race != null && !race.isBlank();
        String normalizedRace = specific ? compact(race) : "";
        var scoped = snapshot.events().stream()
                .filter(row -> !specific || names(row).contains(normalizedRace)).toList();
        int limit = requestedLimit == null ? 10 : Math.clamp(requestedLimit, 1, 30);
        var verified = scoped.stream().filter(row -> row.status() == Status.VERIFIED).sorted(BY_COUNT).toList();
        var ranks = new ArrayList<Ranked>();
        int lastRank = 0;
        for (int position = 0; position < Math.min(verified.size(), limit); position++) {
            Event event = verified.get(position);
            if (position == 0 || !event.verifiedCount().equals(verified.get(position - 1).verifiedCount())) {
                lastRank = position + 1;
            }
            ranks.add(new Ranked(lastRank, event));
        }
        boolean cutoffTie = verified.size() > limit
                && verified.get(limit - 1).verifiedCount().equals(verified.get(limit).verifiedCount());
        var disclosed = scoped.stream().filter(row -> row.status() == Status.DISCLOSED).limit(limit).toList();
        return new Query(!specific || !scoped.isEmpty(), year, snapshot.asOf(),
                snapshot.catalogCoverage() == Coverage.COMPLETE, scoped.size(), verified.size(),
                List.copyOf(ranks), disclosed, cutoffTie);
    }

    static Query unavailable(Integer year) {
        return new Query(false, year == null ? LocalDate.now(CHINA).getYear() : year, null,
                false, 0, 0, List.of(), List.of(), false);
    }

    private Snapshot read(int year) {
        if (snapshotPath == null || !Files.isRegularFile(snapshotPath)) return null;
        try (var input = Files.newInputStream(snapshotPath)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Marathon sub-three snapshot is too large");
            Snapshot loaded = reader.readValue(bytes);
            LocalDate today = LocalDate.now(clock.withZone(CHINA));
            if (loaded == null || loaded.year() != year || loaded.asOf().isAfter(today)) return null;
            return loaded;
        } catch (IOException | RuntimeException exception) {
            log.warn("Ignoring invalid marathon sub-three snapshot");
            return null;
        }
    }

    private static String compact(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Z}\\s]", "");
    }

    private static List<String> names(Event row) {
        var names = new ArrayList<String>();
        names.add(compact(row.name()));
        row.aliases().stream().map(MarathonSub3::compact).forEach(names::add);
        return names;
    }

    public enum Status { VERIFIED, DISCLOSED, PENDING, UNAVAILABLE }
    public enum Coverage { COMPLETE, PARTIAL }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Event(String eventId, String name, LocalDate raceDate, String province, String certLevel,
                        String editionSource, Status status, Integer verifiedCount, Integer finishers,
                        Integer disclosedCount, String sourceUrl, String evidenceSha256, Instant collectedAt,
                        Boolean stale, List<String> aliases, String dateHint, String evidenceKind) {
        public Event {
            stale = Boolean.TRUE.equals(stale);
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            if (eventId == null || !EVENT_ID.matcher(eventId).matches() || !text(name, 160)
                    || !text(province, 80) || !("A".equals(certLevel) || "B".equals(certLevel))
                    || !https(editionSource) || status == null || status != Status.PENDING && raceDate == null
                    || aliases.size() > 20 || aliases.stream().anyMatch(alias -> !text(alias, 80))
                    || dateHint != null && !text(dateHint, 80)) {
                throw new IllegalArgumentException("Invalid marathon edition");
            }
            boolean hasEvidence = https(sourceUrl) && collectedAt != null
                    && evidenceSha256 != null && SHA256.matcher(evidenceSha256).matches();
            boolean valid = switch (status) {
                case VERIFIED -> hasEvidence && verifiedCount != null && verifiedCount >= 0 && verifiedCount <= 150_000
                        && disclosedCount == null && (OFFICIAL_EXACT_COUNT.equals(evidenceKind) && finishers == null
                        || evidenceKind == null && finishers != null && finishers <= 150_000 && verifiedCount <= finishers);
                case DISCLOSED -> hasEvidence && disclosedCount != null && disclosedCount >= 0
                        && disclosedCount <= 150_000 && evidenceKind == null
                        && verifiedCount == null && finishers == null;
                case PENDING, UNAVAILABLE -> sourceUrl == null && collectedAt == null && evidenceSha256 == null
                        && verifiedCount == null && finishers == null && disclosedCount == null && !stale
                        && evidenceKind == null;
            };
            if (!valid) throw new IllegalArgumentException("Invalid marathon sub-three evidence");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Snapshot(int schemaVersion, String scope, int year, LocalDate asOf, Instant publishedAt,
                    Coverage catalogCoverage, String catalogSource, List<Event> events) {
        Snapshot {
            if (schemaVersion != 1 || !SCOPE.equals(scope) || year < 2000 || year > 2199 || asOf == null
                    || publishedAt == null || !asOf.equals(LocalDate.ofInstant(publishedAt, CHINA))
                    || asOf.getYear() < year || catalogCoverage == null || events == null || events.size() > 2000
                    || (catalogSource != null || catalogCoverage == Coverage.COMPLETE) && !https(catalogSource)) {
                throw new IllegalArgumentException("Invalid marathon sub-three snapshot");
            }
            events = List.copyOf(events);
            var ids = new HashSet<String>();
            for (Event event : events) {
                if (!ids.add(event.eventId()) || !event.eventId().endsWith("_" + year)
                        || (event.raceDate() != null && event.raceDate().getYear() != year)
                        || (event.status() != Status.PENDING && event.raceDate().isAfter(asOf))
                        || event.collectedAt() != null && event.collectedAt().isAfter(publishedAt)) {
                    throw new IllegalArgumentException("Invalid marathon sub-three edition or timestamp");
                }
            }
        }
    }

    private static boolean text(String value, int limit) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= limit
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean https(String value) {
        if (!text(value, 2048)) return false;
        try {
            var url = java.net.URI.create(value);
            return "https".equals(url.getScheme()) && url.getHost() != null && url.getUserInfo() == null
                    && (url.getPort() == -1 || url.getPort() == 443);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public record Ranked(int rank, Event event) {}
    public record Query(boolean available, int year, LocalDate asOf, boolean catalogComplete, int cataloguedCount,
                        int verifiedCount, List<Ranked> entries, List<Event> disclosed, boolean cutoffTie) {}
}
