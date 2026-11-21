package cn.watsonzhu.runagent.marathon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static cn.watsonzhu.runagent.marathon.MarathonData.*;
import static org.assertj.core.api.Assertions.assertThat;

class MarathonServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T02:00:00Z"), ZoneOffset.UTC);

    @TempDir Path directory;

    @Test
    void resolvesLatestFinishedHalfMarathonAndNeverSearchesItsFutureEdition() {
        var service = service((series, event, year, distance, deadline) -> {
            throw new AssertionError("Seeds and future editions must not search");
        });

        var latest = service.podium("重庆半马", null, "Women", null, true, false);
        assertThat(latest.selectedYear()).isEqualTo(2025);
        assertThat(latest.podium().distance()).isEqualTo(Distance.HALF_MARATHON);
        assertThat(latest.podium().groups()).singleElement().satisfies(group ->
                assertThat(group.results().getFirst().finishTime()).isEqualTo("01:13:37"));
        assertThat(service.podium("重庆半马", 2026, "All", null, true, false).status())
                .isEqualTo(Status.NOT_STARTED);
    }

    @Test
    void resolvesVerifiedAthleteAliasWithoutSearching() {
        var service = service((series, event, year, distance, deadline) -> {
            throw new AssertionError("Athlete lookup should only read local results");
        });

        assertThat(service.athlete("阿塞法")).singleElement().satisfies(appearance -> {
            assertThat(appearance.year()).isEqualTo(2026);
            assertThat(appearance.result().finishTime()).isEqualTo("02:11:04");
            assertThat(appearance.result().isCourseRecord()).isTrue();
            assertThat(appearance.source().url()).contains("generali-berlin-marathon.com");
        });
    }

    @Test
    void ownerFillsMissingEditionAndVisitorCanReadItAfterRestart() {
        var searches = new AtomicInteger();
        var service = service((series, event, year, distance, deadline) -> {
            searches.incrementAndGet();
            assertThat(series.seriesId()).isEqualTo("chongqing_half");
            assertThat(year).isEqualTo(2024);
            assertThat(event).isNull();
            return Optional.of(result(series.seriesId(), year, distance));
        });

        assertThat(service.podium("重庆半马", 2024, "Men", null, false, false).status())
                .isEqualTo(Status.NOT_CACHED);
        assertThat(searches.get()).isZero();
        assertThat(service.podium("重庆半马", 2024, "Men", null, true, false).status())
                .isEqualTo(Status.PARTIAL);
        var restarted = service(null);
        assertThat(restarted.podium("重庆半马", 2024, "Men", null, false, false).podium())
                .isEqualTo(result("chongqing_half", 2024, Distance.HALF_MARATHON));
        assertThat(restarted.podium("重庆半马", 2024, "Women", null, false, false).status())
                .isEqualTo(Status.NOT_CACHED);
    }

    @Test
    void concurrentMissSharesSearchAndPersistsOneResult() throws Exception {
        var searches = new AtomicInteger();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var service = service((series, event, year, distance, deadline) -> {
            searches.incrementAndGet();
            started.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Test did not release lookup");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return Optional.of(result(series.seriesId(), year, distance));
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.podium("重庆半马", 2024, "Men", null, true, false));
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.podium("重庆半马", 2024, "All", null, true, false));
            release.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS).podium())
                    .isEqualTo(second.get(3, TimeUnit.SECONDS).podium());
        }
        assertThat(searches).hasValue(1);
    }

    @Test
    void failedRefreshPreservesSeedAndInconsistentLookupCannotBePublished() {
        var service = service((series, event, year, distance, deadline) ->
                Optional.of(result(series.seriesId(), 2023, distance)));

        var refreshed = service.podium("重庆半马", 2025, "Women", null, true, true);
        assertThat(refreshed.podium().year()).isEqualTo(2025);
        assertThat(service.podium("重庆半马", 2024, "All", null, true, false).podium()).isNull();
        assertThat(service(null).podium("重庆半马", 2024, "All", null, false, false).status())
                .isEqualTo(Status.NOT_CACHED);
    }

    @Test
    void verifiedWithdrawalPersistsAcrossRestartWithoutDiscardingOtherRanks() {
        var source = new Source("https://www.cqbn.gov.cn/correction", "合成更正来源", CLOCK.instant());
        var service = service((series, event, year, distance, deadline) ->
                Optional.of(new Podium(1, event.eventId(), series.seriesId(), year, event.raceDate(), distance,
                        List.of(new Group(Category.WOMEN, SubCategory.OVERALL, source, List.of(),
                                List.of(new Withdrawal("刘君慧", source)))), null, CLOCK.instant())));
        var refreshed = service.podium("重庆半马", 2025, "Women", null, true, true);

        assertThat(refreshed.status()).isEqualTo(Status.PARTIAL);
        var restarted = service(null).podium("重庆半马", 2025, "Women", null, false, false);
        assertThat(restarted.podium().groups().getFirst().results()).extracting(Rank::athleteName)
                .containsExactly("叶瑞", "方可馨");
        assertThat(restarted.podium().groups().getFirst().withdrawals()).containsExactly(new Withdrawal("刘君慧", source));
    }

    @Test
    void verifiedOwnerCanRepairCorruptCacheWithoutExposingBundledRanksBeforeRepair() throws Exception {
        Files.writeString(directory.resolve("chongqing_half_2024.half_marathon.json"), "broken");
        var service = service((series, event, year, distance, deadline) ->
                Optional.of(result(series.seriesId(), year, distance)));
        assertThat(service.podium("重庆半马", 2024, "Men", null, false, false).status())
                .isEqualTo(Status.UNAVAILABLE);
        assertThat(service.podium("重庆半马", 2024, "Men", null, true, true).status())
                .isEqualTo(Status.PARTIAL);
        assertThat(service(null).podium("重庆半马", 2024, "Men", null, false, false).podium())
                .isEqualTo(result("chongqing_half", 2024, Distance.HALF_MARATHON));
    }

    @Test
    void scheduledLookupStartsAfterRaceDayAndSkipsCompleteCachedPodiums() {
        var searches = new AtomicInteger();
        MarathonResultLookup provider = (series, event, year, distance, deadline) -> {
            searches.incrementAndGet();
            assertThat(event.eventId()).isEqualTo("nanjing_marathon_2026");
            assertThat(distance).isEqualTo(Distance.MARATHON);
            return Optional.of(scheduledResult(event, distance, Category.MEN, Category.WOMEN));
        };

        service(Clock.fixed(Instant.parse("2026-11-21T18:00:00Z"), ZoneOffset.UTC), provider).refreshRecentPodiums();
        assertThat(searches).hasValue(0);

        var nextMorning = Clock.fixed(Instant.parse("2026-11-22T18:00:00Z"), ZoneOffset.UTC);
        var service = service(nextMorning, provider);
        service.refreshRecentPodiums();
        service.refreshRecentPodiums();
        assertThat(searches).hasValue(1);
        assertThat(service(nextMorning, null).podium("南马", null, "All", null, false, false).status())
                .isEqualTo(Status.READY);
    }

    @Test
    void scheduledLookupRetriesUnavailableAndPartialResultsOnFollowingDays() {
        var searches = new AtomicInteger();
        MarathonResultLookup provider = (series, event, year, distance, deadline) -> {
            assertThat(event.eventId()).isEqualTo("nanjing_marathon_2026");
            return switch (searches.incrementAndGet()) {
                case 1 -> Optional.empty();
                case 2 -> Optional.of(scheduledResult(event, distance, Category.MEN));
                default -> Optional.of(scheduledResult(event, distance, Category.WOMEN));
            };
        };

        var first = service(Clock.fixed(Instant.parse("2026-11-22T18:00:00Z"), ZoneOffset.UTC), provider);
        first.refreshRecentPodiums();
        assertThat(first.podium("南马", 2026, "All", null, false, false).podium()).isNull();

        var second = service(Clock.fixed(Instant.parse("2026-11-23T18:00:00Z"), ZoneOffset.UTC), provider);
        second.refreshRecentPodiums();
        assertThat(second.podium("南马", 2026, "All", null, false, false).status()).isEqualTo(Status.PARTIAL);

        var third = service(Clock.fixed(Instant.parse("2026-11-24T18:00:00Z"), ZoneOffset.UTC), provider);
        third.refreshRecentPodiums();
        assertThat(third.podium("南马", 2026, "All", null, false, false).status()).isEqualTo(Status.READY);
        assertThat(searches).hasValue(3);
    }

    @Test
    void scheduledAndManualLookupsShareTheDailySearchBudget() {
        var searches = new AtomicInteger();
        var service = service(Clock.fixed(Instant.parse("2026-11-22T18:00:00Z"), ZoneOffset.UTC),
                (series, event, year, distance, deadline) -> {
                    searches.incrementAndGet();
                    return Optional.empty();
                });

        for (int year = 2000; year < 2012; year++) {
            service.podium("南马", year, "All", null, true, false);
        }
        service.refreshRecentPodiums();

        assertThat(searches).hasValue(12);
        assertThat(service.podium("南马", 2026, "All", null, false, false).podium()).isNull();
    }

    private MarathonService service(MarathonResultLookup lookup) {
        return service(CLOCK, lookup);
    }

    private MarathonService service(Clock clock, MarathonResultLookup lookup) {
        var json = JsonMapper.builder().findAndAddModules().build();
        return new MarathonService(new MarathonCalendar(json, null, clock),
                new MarathonPodiumStore(json, directory), lookup, clock);
    }

    private static Podium scheduledResult(Event event, Distance distance, Category... categories) {
        var source = new Source("https://example.test/results", "合成榜单", CLOCK.instant());
        var groups = java.util.Arrays.stream(categories).map(category -> new Group(category, SubCategory.OVERALL,
                source, List.of(new Rank(1, "示例冠军", null, "02:11:10", null),
                new Rank(2, "示例亚军", null, "02:13:10", null),
                new Rank(3, "示例季军", null, "02:15:10", null)))).toList();
        return new Podium(1, event.eventId(), event.seriesId(), event.year(), event.raceDate(), distance,
                groups, null, CLOCK.instant());
    }

    private static Podium result(String seriesId, int year, Distance distance) {
        return new Podium(1, seriesId + "_" + year, seriesId, year, LocalDate.of(year, 12, 14), distance,
                List.of(new Group(Category.MEN, SubCategory.OVERALL,
                        new Source("https://www.cqbn.gov.cn/test", "合成测试来源", CLOCK.instant()),
                        List.of(new Rank(1, "示例选手", null, "01:10:20", null)))),
                null, CLOCK.instant());
    }
}
