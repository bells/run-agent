package cn.watsonzhu.runagent.marathon;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static cn.watsonzhu.runagent.marathon.MarathonUpdates.*;
import static org.assertj.core.api.Assertions.assertThat;

class MarathonUpdatesTest {
    private static final Query REGISTRATION = race(Kind.REGISTRATION, "上海马拉松");
    private static final String CAPACITY = "2026上海马拉松全程马拉松参赛规模为30,000人";

    @Test
    void keepsCapacityDistinctFromApplicationsAndExcludesUnsupportedNumbersAndOldEditions() {
        var official = source("race.example.org", "2026上海马拉松", CAPACITY);
        var old = source("media.example.org", "2025上海马拉松", "2025上海马拉松全马报名人数19万人");
        var reply = updates(new Lookup(List.of(official, old), List.of(
                new Finding(0, Fact.CAPACITY, "30,000", null, null, CAPACITY),
                new Finding(0, Fact.APPLICATIONS, "30,000", null, null, CAPACITY),
                new Finding(0, Fact.CAPACITY, "50,000", null, null, CAPACITY),
                new Finding(1, Fact.APPLICATIONS, "19万", null, null, old.text())))).answer(REGISTRATION, false);

        assertThat(reply).contains("参赛名额或规模", "30,000", "race.example.org", "2026-11-27")
                .doesNotContain("50,000", "19万", "media.example.org", "预报名人数**");
    }

    @Test
    void distinguishesInviteesFromConfirmedAthletesAndReportsConflictingCounts() {
        var official = source("race.example.org", "2026上海马拉松", "已邀请王小雨，李小林确认参赛。");
        var roster = new Lookup(List.of(official), List.of(
                new Finding(0, Fact.INVITED, "王小雨", null, null, "已邀请王小雨"),
                new Finding(0, Fact.CONFIRMED, "李小林", null, null, "李小林确认参赛"),
                new Finding(0, Fact.CONFIRMED, "王小雨", null, null, "已邀请王小雨"),
                new Finding(0, Fact.CONFIRMED, "赵小明", null, null, "李小林确认参赛")));
        assertThat(updates(roster).answer(race(Kind.LINEUP, "上海马拉松"), false))
                .contains("受邀", "王小雨", "官方确认", "李小林").doesNotContain("赵小明");

        var media = source("media.example.org", "2026上海马拉松", "预报名人数超过15万人");
        var other = source("sports.example.org", "2026上海马拉松", "预报名人数超过16万人");
        var counts = new Lookup(List.of(media, other), List.of(
                new Finding(0, Fact.APPLICATIONS, "超过15万", null, null, media.text()),
                new Finding(1, Fact.APPLICATIONS, "超过16万", null, null, other.text())));
        assertThat(updates(counts).answer(REGISTRATION, false)).contains("口径", "超过15万", "超过16万");
    }

    @Test
    void preservesApproximateCountsAndDoesNotTreatForecastsAsActualStarters() {
        String applications = "本届预报名人数超过15万人。";
        String expected = "预计有3万人实际参赛。";
        var record = source("media.example.org", "2026上海马拉松", applications + expected);
        var lookup = new Lookup(List.of(record), List.of(
                new Finding(0, Fact.APPLICATIONS, "15万", null, null, applications),
                new Finding(0, Fact.APPLICATIONS, "超过15万", null, null, applications),
                new Finding(0, Fact.STARTERS, "3万", null, null, expected)));
        assertThat(updates(lookup).answer(REGISTRATION, false)).contains("超过15万")
                .doesNotContain("· 15万", "实际参赛人数");
    }

    @Test
    void requiresPlanToMatchTheRequestedAthleteEventAndDate() {
        var source = source("media.example.org", "2026年跑步报道", "张水华表示，11月29日将参加仙游半程马拉松。");
        var query = new Query(Kind.ATHLETE, "张水华", 2026, LocalDate.parse("2026-11-29"),
                LocalDate.parse("2026-11-29"), null, List.of(), List.of());
        var lookup = new Lookup(List.of(source), List.of(
                new Finding(0, Fact.PLAN, null, "仙游半程马拉松", LocalDate.parse("2026-11-29"), source.text()),
                new Finding(0, Fact.PLAN, null, "深圳马拉松", LocalDate.parse("2026-11-29"), source.text()),
                new Finding(0, Fact.PLAN, null, "仙游半程马拉松", LocalDate.parse("2026-11-30"), source.text())));
        assertThat(updates(lookup).answer(query, false)).contains("媒体报道", "仙游半程马拉松", "2026-11-29")
                .doesNotContain("深圳马拉松", "2026-11-30");
    }

    @Test
    void acceptsOfficialEnglishCapacityAndKeepsActualStartersAndApplicationsSeparate() {
        var query = new Query(Kind.REGISTRATION, "上海马拉松", 2026, null, null, null,
                List.of("Shanghai Marathon"), List.of("race.example.org"));
        String capacity = "The marathon will expand its capacity from 23,000 to 30,000 runners.";
        String mixed = "预报名人数为15万人、实际参赛人数为3万人。";
        var lookup = new Lookup(List.of(source("race.example.org", "2026 Shanghai Marathon", capacity),
                source("sports.example.org", "2026上海马拉松", mixed)), List.of(
                new Finding(0, Fact.CAPACITY, "30,000", null, null, capacity),
                new Finding(1, Fact.APPLICATIONS, "15万", null, null, mixed),
                new Finding(1, Fact.STARTERS, "3万", null, null, mixed),
                new Finding(1, Fact.APPLICATIONS, "3万", null, null, mixed)));
        assertThat(updates(lookup).answer(query, false)).contains("30,000", "预报名人数** · 15万", "实际参赛人数** · 3万")
                .doesNotContain("预报名人数** · 3万");
    }

    @Test
    void acceptsAPlanDatedByNearbyCoverageWhenTheSearchResultHasNoPublicationDate() {
        String plan = "黄石马拉松赛后张水华确认了下一站行程：11月29日，她将参加福建仙游半程马拉松。";
        var source = new Source("张水华本周回家乡参赛", URI.create("https://sport.example/report"), null,
                "11月22日，2026黄石马拉松鸣枪开跑。 " + plan);
        LocalDate date = LocalDate.parse("2026-11-29");
        var query = new Query(Kind.ATHLETE, "张水华", 2026, date, date, null, List.of(), List.of());
        var lookup = new Lookup(List.of(source), List.of(
                new Finding(0, Fact.PLAN, null, "福建仙游半程马拉松", date, plan)));
        assertThat(updates(lookup).answer(query, false)).contains("福建仙游半程马拉松", "2026-11-29");
    }

    @Test
    void rejectsUnrelatedRaceAndAmbiguousQuantitiesInAComparison() {
        String otherRace = "杭州马拉松赛事规模为30,000人。";
        String combined = "15,000人参与抽签争夺5,000个名额。";
        String valid = "比赛设置6,000个名额。";
        var first = source("race.example.org", "2026年上海马拉松赛事观察", otherRace + combined);
        var second = source("sport.example", "2026上马抽签结果公布", valid);
        var lookup = new Lookup(List.of(first, second), List.of(
                new Finding(0, Fact.CAPACITY, "30,000", null, null, otherRace),
                new Finding(0, Fact.CAPACITY, "15,000", null, null, combined),
                new Finding(1, Fact.CAPACITY, "6,000", null, null, valid)));
        assertThat(updates(lookup).answer(REGISTRATION, false)).contains("6,000")
                .doesNotContain("30,000", "15,000");
    }

    @Test
    void citesTheNewCapacityInAnExplicitComparisonAndMatchesCountsExpressedInTenThousands() {
        String increase = "竞赛规模由往届的23000人 提升至30000人";
        String registration = "赛事报名人数达到38.19万人，创下新高";
        var official = source("race.example.org", "2026上马报名即将开启！规模提升至30000人", increase);
        var media = source("media.example.org", "2026上马报名人数创新高 全程马拉松规模扩至三万人", registration);
        var lookup = new Lookup(List.of(official, media), List.of(
                new Finding(0, Fact.CAPACITY, "30000", null, null, increase),
                new Finding(0, Fact.CAPACITY, "23000", null, null, increase),
                new Finding(1, Fact.REGISTERED, "381900", null, null, registration)));
        assertThat(updates(lookup).answer(REGISTRATION, false))
                .contains("参赛名额或规模** · 30000", "报名人数** · 381900")
                .doesNotContain("23000");
    }

    @Test
    void doesNotCombineAnotherRunnerAndRaceFromSeparateClauses() {
        String text = "张水华将参加泉州半程马拉松。11月29日李华将参加仙游半程马拉松。";
        var source = source("sports.example.org", "2026年跑步报道", text);
        var day = LocalDate.parse("2026-11-29");
        var query = new Query(Kind.ATHLETE, "张水华", 2026, day, day, null, List.of(), List.of());
        var lookup = new Lookup(List.of(source), List.of(new Finding(0, Fact.PLAN, null, "仙游半程马拉松", day, text)));
        assertThat(updates(lookup).answer(query, false)).contains("未查到").doesNotContain("仙游");
    }

    @Test
    void sharesCacheBeforeVisitorQuotaAndResetsAtShanghaiMidnight() {
        var now = new AtomicReference<>(Instant.parse("2026-11-28T15:56:00Z"));
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
            @Override public Instant instant() { return now.get(); }
        };
        var calls = new AtomicInteger();
        var updates = new MarathonUpdates(clock, 1, 1, query -> {
            calls.incrementAndGet();
            return new Lookup(List.of(), List.of());
        });

        assertThat(updates.answer(REGISTRATION, false)).contains("未查到");
        assertThat(updates.answer(race(Kind.LINEUP, "上海马拉松"), false)).contains("访客查询额度");
        assertThat(updates.answer(REGISTRATION, false)).contains("未查到");
        assertThat(calls).hasValue(1);
        assertThat(updates.answer(race(Kind.LINEUP, "上海马拉松"), true)).contains("未查到");
        assertThat(calls).hasValue(2);

        now.set(Instant.parse("2026-11-28T16:02:00Z"));
        assertThat(updates.answer(REGISTRATION, false)).contains("未查到");
        assertThat(calls).hasValue(3);
    }

    @Test
    void aDependencyFailureIsNotCachedAsAnEmptySearch() {
        var calls = new AtomicInteger();
        var updates = new MarathonUpdates(Clock.systemUTC(), 2, 2, query -> {
            if (calls.getAndIncrement() == 0) throw new IllegalStateException("provider failed");
            return new Lookup(List.of(), List.of());
        });
        assertThat(updates.answer(REGISTRATION, false)).contains("暂时不可用");
        assertThat(updates.answer(REGISTRATION, false)).contains("未查到");
        assertThat(calls).hasValue(2);
    }

    private static Query race(Kind kind, String name) {
        return new Query(kind, name, 2026, null, null, null, List.of("上马"), List.of("race.example.org"));
    }

    private static Source source(String host, String title, String text) {
        return new Source(title, URI.create("https://" + host + "/report"), LocalDate.parse("2026-11-27"), text);
    }

    private static MarathonUpdates updates(Lookup lookup) {
        return new MarathonUpdates(Clock.systemUTC(), 12, 24, query -> lookup);
    }
}
