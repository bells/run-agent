package cn.watsonzhu.runagent.marathon;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static cn.watsonzhu.runagent.marathon.MarathonData.*;

public final class MarathonService {
    private static final Logger log = LoggerFactory.getLogger(MarathonService.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long LOOKUP_BUDGET = Duration.ofSeconds(10).toNanos();
    private static final long MISS_COOLDOWN = Duration.ofMinutes(3).toNanos();
    private static final int DAILY_LOOKUPS = 12;
    // Published Chinese spellings used to find the same athletes in the organizer's results.
    private static final Map<String, String> ATHLETE_ALIASES = Map.of(
            "阿塞法", "tigstassefa", "蒂格斯特阿塞法", "tigstassefa", "阿多拉", "guyeadola");

    private final MarathonCalendar calendar;
    private final MarathonPodiumStore store;
    private final MarathonResultLookup lookup;
    private final Clock clock;
    private final Semaphore permits = new Semaphore(2);
    private final ConcurrentHashMap<MarathonPodiumStore.Key, CompletableFuture<Optional<Podium>>> inFlight =
            new ConcurrentHashMap<>();
    private final LinkedHashMap<MarathonPodiumStore.Key, Long> recentFailures = new LinkedHashMap<>();
    private LocalDate quotaDay;
    private int attemptsToday;

    MarathonService(MarathonCalendar calendar, MarathonPodiumStore store, MarathonResultLookup lookup, Clock clock) {
        this.calendar = calendar;
        this.store = store;
        this.lookup = lookup;
        this.clock = clock;
    }

    public List<Series> series() {
        return calendar.current().series();
    }

    public List<String> locations() {
        return calendar.current().events().stream()
                .flatMap(event -> Stream.of(event.city(), event.province()))
                .filter(java.util.Objects::nonNull).distinct().toList();
    }

    public CalendarResult calendar(String city, String month, String distance, Integer limit, String raceName,
                                    boolean featured) {
        return calendar.query(city, month, distance, limit, raceName, featured);
    }

    public PodiumResult podium(String raceName, Integer year, String category, String distance, boolean allowFill,
                               boolean refresh) {
        if (raceName == null || raceName.isBlank() || raceName.length() > 120
                || year != null && (year < 1900 || year > 2200)) return status(Status.INVALID, year, null);
        var candidates = MarathonCalendar.matchSeries(series(), raceName);
        if (candidates.isEmpty()) return status(Status.NO_MATCH, year, null);
        if (candidates.size() > 1) {
            return new PodiumResult(Status.AMBIGUOUS, null, year,
                    candidates.stream().map(Series::name).limit(8).toList(), null);
        }
        var series = candidates.getFirst();
        try {
            return resolvePodium(series, year, category, distance, allowFill && lookup != null, refresh);
        } catch (IllegalArgumentException exception) {
            return status(Status.INVALID, year, series.name());
        } catch (MarathonPodiumStore.Unavailable exception) {
            return status(Status.UNAVAILABLE, year, series.name());
        }
    }

    private PodiumResult resolvePodium(Series series, Integer year, String category, String requestedDistance,
                                       boolean allowFill, boolean refresh) {
        Category selected = category == null || category.isBlank() || "all".equalsIgnoreCase(category.strip())
                ? null : Category.valueOf(category.strip().toUpperCase(Locale.ROOT));
        var events = calendar.current().events().stream()
                .filter(event -> event.seriesId().equals(series.seriesId())).toList();
        var supported = events.stream().flatMap(event -> event.distances().stream()).distinct().toList();
        Distance distance = requestedDistance == null || requestedDistance.isBlank()
                ? supported.size() == 1 ? supported.getFirst() : Distance.MARATHON
                : Distance.parse(requestedDistance);
        if (!supported.isEmpty() && !supported.contains(distance)) return status(Status.NO_MATCH, year, series.name());
        var eligible = events.stream().filter(event -> event.distances().contains(distance)).toList();
        if (year != null) {
            return readEdition(series, edition(eligible, year), year, distance, selected, allowFill, refresh);
        }
        return newest(series, eligible, distance, selected, allowFill, refresh);
    }

    private PodiumResult newest(Series series, List<Event> events, Distance distance, Category category,
                                boolean allowFill, boolean refresh) {
        var verifiedCache = store.all().stream().filter(podium -> podium.seriesId().equals(series.seriesId())
                && podium.distance() == distance)
                .filter(podium -> {
                    var published = edition(events, podium.year());
                    return published == null ? !podium.raceDate().isAfter(today())
                            : canQuery(published, distance) && published.raceDate().equals(podium.raceDate());
                }).max(Comparator.comparing(Podium::raceDate)).orElse(null);
        var completed = events.stream().filter(event -> event.status() == EventStatus.COMPLETED)
                .filter(event -> canQuery(event, distance)).max(Comparator.comparing(Event::raceDate)).orElse(null);
        Integer confirmedYear = completed == null ? null : completed.year();
        LocalDate confirmedDate = completed == null ? null : completed.raceDate();
        if (verifiedCache != null && (confirmedDate == null || verifiedCache.raceDate().isAfter(confirmedDate))) {
            confirmedYear = verifiedCache.year();
            confirmedDate = verifiedCache.raceDate();
        }
        var newestUnverified = newerUnverified(events, distance, confirmedDate);
        if (allowFill && newestUnverified.isPresent()) {
            var event = newestUnverified.get();
            var result = readEdition(series, event, event.year(), distance, category, true, refresh);
            if (result.podium() != null || confirmedYear == null) return result;
        }
        if (confirmedYear == null) {
            if (newestUnverified.isPresent()) return status(Status.PENDING, newestUnverified.get().year(), series.name());
            return status(Status.NO_MATCH, null, series.name());
        }
        var result = readEdition(series, edition(events, confirmedYear), confirmedYear, distance, category,
                allowFill && newestUnverified.isEmpty(), refresh);
        return new PodiumResult(result.status(), result.podium(), result.selectedYear(), result.candidates(),
                result.eventName(), newestUnverified.map(Event::year).orElse(null));
    }

    private Optional<Event> newerUnverified(List<Event> events, Distance distance, LocalDate confirmedDate) {
        return events.stream().filter(event -> event.status() != EventStatus.COMPLETED)
                .filter(event -> canQuery(event, distance))
                .filter(event -> confirmedDate == null || event.raceDate().isAfter(confirmedDate))
                .max(Comparator.comparing(Event::raceDate));
    }

    private PodiumResult readEdition(Series series, Event event, int year, Distance distance, Category category,
                                     boolean allowFill, boolean refresh) {
        if (event != null && !canQuery(event, distance)) {
            var state = event.status() == EventStatus.CANCELLED || event.status() == EventStatus.POSTPONED
                    ? Status.PENDING : Status.NOT_STARTED;
            return status(state, year, series.name());
        }
        if (event == null && year > today().getYear()) return status(Status.NOT_STARTED, year, series.name());
        var key = new MarathonPodiumStore.Key(series.seriesId() + "_" + year, distance);
        var cached = readCache(key, event, allowFill).orElse(null);
        boolean hit = hasGroup(cached, category);
        if ((hit || hasWithdrawal(cached, category)) && (!allowFill || !refresh)) {
            log.debug("marathon_podium_lookup status=CACHE_HIT");
            return project(cached, category, series.name());
        }
        if (!allowFill) return status(cached == null && event != null && event.status() == EventStatus.COMPLETED
                ? Status.PENDING : Status.NOT_CACHED, year, series.name());

        var loaded = fill(series, event, year, key, category, refresh).orElse(cached);
        return hasGroup(loaded, category) || hasWithdrawal(loaded, category) ? project(loaded, category, series.name())
                : status(Status.UNAVAILABLE, year, series.name());
    }

    private Optional<Podium> fill(Series series, Event event, int year, MarathonPodiumStore.Key key, Category category,
                                  boolean refresh) {
        long deadline = System.nanoTime() + LOOKUP_BUDGET;
        var mine = new CompletableFuture<Optional<Podium>>();
        var existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) return await(existing, deadline);
        try {
            var loaded = attemptFill(series, event, year, key, category, refresh, deadline);
            mine.complete(loaded);
            return loaded;
        } finally {
            mine.complete(Optional.empty());
            inFlight.remove(key, mine);
        }
    }

    private Optional<Podium> attemptFill(Series series, Event event, int year, MarathonPodiumStore.Key key,
                                         Category category, boolean refresh, long deadline) {
        var previous = readCache(key, event, true).orElse(null);
        if (!refresh && hasGroup(previous, category)) return Optional.of(previous);
        if (!permits.tryAcquire()) return Optional.empty();
        long started = System.nanoTime();
        try {
            if (!admit(key, refresh)) return Optional.empty();
            var result = lookup.lookup(series, event, year, key.distance(), deadline);
            if (result.isPresent() && consistent(result.get(), key, series, event, year)
                    && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                var verified = merge(previous, result.get());
                store.save(verified);
                if (!hasGroup(verified, category)) failed(key);
                log.info("marathon_podium_lookup status=VERIFIED elapsedMs={}", elapsedMillis(started));
                return Optional.of(verified);
            }
            failed(key);
            log.info("marathon_podium_lookup status=NO_VERIFIED_EVIDENCE elapsedMs={}", elapsedMillis(started));
        } catch (RuntimeException exception) {
            failed(key);
            log.warn("marathon_podium_lookup status=UNAVAILABLE elapsedMs={}", elapsedMillis(started));
        } finally {
            permits.release();
        }
        return Optional.empty();
    }

    private Optional<Podium> readCache(MarathonPodiumStore.Key key, Event event, boolean canRepair) {
        try {
            return store.find(key).filter(entry -> event == null || event.raceDate().equals(entry.raceDate()));
        } catch (MarathonPodiumStore.Unavailable exception) {
            if (!canRepair) throw exception;
            log.warn("marathon_podium_entry status=INVALID_PENDING_REPAIR");
            return Optional.empty();
        }
    }

    private boolean consistent(Podium result, MarathonPodiumStore.Key key, Series series, Event event, int year) {
        return key.equals(MarathonPodiumStore.Key.of(result)) && result.seriesId().equals(series.seriesId())
                && result.year() == year && !result.groups().isEmpty()
                && (event == null ? !result.raceDate().isAfter(today()) : event.raceDate().equals(result.raceDate()));
    }

    private static Podium merge(Podium previous, Podium update) {
        if (previous == null) return update;
        var groups = new ArrayList<>(previous.groups());
        for (var fresh : update.groups()) {
            var old = groups.stream().filter(group -> group.category() == fresh.category()
                    && group.subCategory() == fresh.subCategory()).findFirst();
            if (old.isEmpty()) {
                groups.add(fresh);
            } else {
                groups.set(groups.indexOf(old.get()), mergeGroup(old.get(), fresh));
            }
        }
        var revisedHighlights = update.highlights();
        if (revisedHighlights == null && update.groups().stream().allMatch(group -> group.withdrawals().isEmpty())) {
            revisedHighlights = previous.highlights();
        }
        return new Podium(update.schemaVersion(), update.eventId(), update.seriesId(), update.year(), update.raceDate(),
                update.distance(), groups, revisedHighlights, update.updatedAt());
    }

    private static Group mergeGroup(Group previous, Group update) {
        var withdrawals = new LinkedHashMap<String, Withdrawal>();
        Stream.concat(previous.withdrawals().stream(), update.withdrawals().stream())
                .forEach(withdrawal -> withdrawals.put(normalized(withdrawal.athleteName()), withdrawal));
        var revised = update.results().stream()
                .filter(rank -> !withdrawals.containsKey(normalized(rank.athleteName()))).toList();
        var retained = previous.results().stream()
                .filter(rank -> !withdrawals.containsKey(normalized(rank.athleteName()))).toList();
        // Preserve the older citation when a partial update leaves some of its ranks in use.
        boolean conflicting = revised.stream().anyMatch(rank -> retained.stream()
                .anyMatch(existing -> rank.rank() == existing.rank())
                && retained.stream().noneMatch(existing -> rank.rank() == existing.rank()
                        && normalized(rank.athleteName()).equals(normalized(existing.athleteName()))
                        && rank.finishTime().equals(existing.finishTime())));
        boolean replace = update.source().url().equals(previous.source().url()) && conflicting
                || !conflicting && revised.size() >= retained.size();
        return new Group(update.category(), update.subCategory(), replace ? update.source() : previous.source(),
                replace ? revised : retained, withdrawals.values().stream().skip(Math.max(0, withdrawals.size() - 24)).toList());
    }

    private synchronized boolean admit(MarathonPodiumStore.Key key, boolean refresh) {
        long now = System.nanoTime();
        var lastFailure = recentFailures.get(key);
        if (!refresh && lastFailure != null && now - lastFailure < MISS_COOLDOWN) return false;
        LocalDate today = today();
        if (!today.equals(quotaDay)) {
            quotaDay = today;
            attemptsToday = 0;
        }
        if (attemptsToday >= DAILY_LOOKUPS) return false;
        attemptsToday++;
        return true;
    }

    private synchronized void failed(MarathonPodiumStore.Key key) {
        recentFailures.put(key, System.nanoTime());
        if (recentFailures.size() > 256) recentFailures.pollFirstEntry();
    }

    private static Optional<Podium> await(CompletableFuture<Optional<Podium>> future, long deadline) {
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException exception) {
            return Optional.empty();
        }
    }

    private boolean canQuery(Event event, Distance distance) {
        if (event.status() == EventStatus.CANCELLED || event.status() == EventStatus.POSTPONED) return false;
        var local = ZonedDateTime.now(clock.withZone(ZoneId.of(event.timezone())));
        int compared = event.raceDate().compareTo(local.toLocalDate());
        var start = event.startTimes().get(distance);
        return compared < 0 || compared == 0 && (start == null || !local.toLocalTime().isBefore(start));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(DEFAULT_ZONE));
    }

    private static Event edition(List<Event> events, int year) {
        return events.stream().filter(event -> event.year() == year).findFirst().orElse(null);
    }

    public List<AthleteResult> athlete(String name) {
        if (name == null || name.isBlank() || name.length() > 120) return List.of();
        String normalizedName = normalized(name);
        String query = ATHLETE_ALIASES.getOrDefault(normalizedName, normalizedName);
        var seriesNames = new LinkedHashMap<String, String>();
        series().forEach(series -> seriesNames.put(series.seriesId(), series.name()));
        LocalDate today = today();
        return store.all().stream().filter(podium -> !podium.raceDate().isBefore(today.minusYears(2))
                        && !podium.raceDate().isAfter(today) && seriesNames.containsKey(podium.seriesId()))
                .sorted(Comparator.comparing(Podium::raceDate).reversed())
                .flatMap(podium -> podium.groups().stream().flatMap(group -> group.results().stream()
                        .filter(rank -> normalized(rank.athleteName()).equals(query))
                        .map(rank -> new AthleteResult(seriesNames.get(podium.seriesId()), podium.year(),
                                podium.distance(), group.category(), group.subCategory(), rank, group.source()))))
                .limit(20).toList();
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Z}\\p{P}\\s]+", "");
    }

    private static boolean hasGroup(Podium podium, Category category) {
        return podium != null && podium.groups().stream()
                .anyMatch(group -> (category == null || category == group.category()) && !group.results().isEmpty());
    }

    private static boolean hasWithdrawal(Podium podium, Category category) {
        return podium != null && podium.groups().stream()
                .anyMatch(group -> (category == null || category == group.category()) && !group.withdrawals().isEmpty());
    }

    private static PodiumResult project(Podium source, Category category, String name) {
        var groups = source.groups().stream()
                .filter(group -> category == null || category == group.category()).toList();
        long complete = groups.stream().filter(group -> group.subCategory() == SubCategory.OVERALL
                && group.results().stream().map(Rank::rank).distinct().count() == 3).count();
        Status state = groups.stream().allMatch(group -> group.results().isEmpty()) ? Status.PENDING
                : complete == (category == null ? 2 : 1) ? Status.READY : Status.PARTIAL;
        var podium = new Podium(source.schemaVersion(), source.eventId(), source.seriesId(), source.year(),
                source.raceDate(), source.distance(), groups, category == null ? source.highlights() : null,
                source.updatedAt());
        return new PodiumResult(state, podium, source.year(), List.of(), name);
    }

    private static long elapsedMillis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private static PodiumResult status(Status status, Integer year, String name) {
        return new PodiumResult(status, null, year, List.of(), name);
    }

    public record PodiumResult(Status status, Podium podium, Integer selectedYear, List<String> candidates,
                               String eventName, Integer unverifiedLaterYear) {
        public PodiumResult(Status status, Podium podium, Integer selectedYear, List<String> candidates, String eventName) {
            this(status, podium, selectedYear, candidates, eventName, null);
        }
    }

    public record AthleteResult(String eventName, int year, Distance distance, Category category,
                                SubCategory subCategory, Rank result, Source source) {}
}
