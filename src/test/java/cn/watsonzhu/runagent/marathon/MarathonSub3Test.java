package cn.watsonzhu.runagent.marathon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import cn.watsonzhu.runagent.evidence.AdviceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class MarathonSub3Test {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-26T08:00:00Z"), ZoneOffset.UTC);
    private static final String SNAPSHOT = """
            {"schema_version":1,"year":2026,"scope":"CN_MAINLAND_CAA_AB_MARATHON",
              "as_of":"2026-11-26","published_at":"2026-11-26T08:00:00Z","catalog_coverage":"PARTIAL",
              "events":[
              {"event_id":"delta_2026","name":"丁城马拉松","race_date":"2026-05-01","province":"江苏",
               "cert_level":"B","edition_source":"https://example.com/delta","status":"VERIFIED",
               "verified_count":200,"finishers":350,
               "source_url":"https://example.com/delta/results","collected_at":"2026-11-26T08:00:00Z",
               "evidence_sha256":"1111111111111111111111111111111111111111111111111111111111111111"},
              {"event_id":"alpha_2026","name":"甲城马拉松","race_date":"2026-06-01","province":"江苏",
               "cert_level":"A","edition_source":"https://example.com/alpha","status":"VERIFIED",
               "verified_count":250,"finishers":400,
               "source_url":"https://example.com/alpha/results","collected_at":"2026-11-26T08:00:00Z",
               "evidence_sha256":"2222222222222222222222222222222222222222222222222222222222222222"},
              {"event_id":"beta_2026","name":"乙城马拉松","race_date":"2026-05-01","province":"浙江",
               "cert_level":"A","edition_source":"https://example.com/beta","status":"DISCLOSED",
               "disclosed_count":500,"source_url":"https://example.com/beta/report",
               "collected_at":"2026-11-26T08:00:00Z",
               "evidence_sha256":"3333333333333333333333333333333333333333333333333333333333333333"}]}
            """;

    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void leaderboardSeparatesUnverifiedDisclosuresAndReportsCoverage(@TempDir Path dir) throws Exception {
        var snapshot = Files.writeString(dir.resolve("sub3.json"), SNAPSHOT);
        var warehouse = new MarathonSub3(json, snapshot, CLOCK);
        var rankings = warehouse.query(2026, null, 10);

        assertThat(rankings.entries()).extracting(row -> row.event().name())
                .containsExactly("甲城马拉松", "丁城马拉松");
        assertThat(rankings.disclosed()).extracting(MarathonSub3.Event::disclosedCount).containsExactly(500);
        assertThat(rankings.cataloguedCount()).isEqualTo(3);
        assertThat(rankings.verifiedCount()).isEqualTo(2);
        assertThat(rankings.catalogComplete()).isFalse();
    }

    @Test
    void routesAnnualAndSingleRaceFactsWithoutMakingTrainingADataQuery(@TempDir Path dir) throws Exception {
        var snapshot = Files.writeString(dir.resolve("sub3.json"), SNAPSHOT);
        var marathon = new MarathonService(new MarathonCalendar(json, null, CLOCK),
                new MarathonPodiumStore(json, null), null, CLOCK);
        var chat = new MarathonChat(marathon, CLOCK, new MarathonSub3(json, snapshot, CLOCK));

        var ranking = chat.query("2026年中国马拉松破3人数前十位的马拉松", AdviceContext.NONE, false).orElseThrow();
        assertThat(ranking.content()).contains("甲城马拉松", "250", "已核验", "2026-11-26").doesNotContain("500");
        assertThat(ranking.adviceRequested()).isFalse();
        var disclosure = chat.query("2026年乙城马拉松有多少人破三？", AdviceContext.NONE, false).orElseThrow();
        assertThat(disclosure.content()).contains("乙城马拉松", "500", "口径", "https://example.com/beta/report");
        assertThat(chat.query("全马想破三该怎么训练？", AdviceContext.NONE, false)).isEmpty();
    }

    @Test
    void acceptsAnExactOfficialAggregateWithoutInventingTotalFinishers(@TempDir Path dir) throws Exception {
        var snapshot = Files.writeString(dir.resolve("sub3.json"), SNAPSHOT.replace(
                "\"finishers\":350", "\"evidence_kind\":\"OFFICIAL_EXACT_COUNT\""));
        var result = new MarathonSub3(json, snapshot, CLOCK).query(2026, "丁城马拉松", 10);
        assertThat(result.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.event().verifiedCount()).isEqualTo(200);
            assertThat(entry.event().finishers()).isNull();
        });
    }

    @Test
    void rejectsCountsAboveFinishersAndWrongYear(@TempDir Path dir) throws Exception {
        var snapshot = Files.writeString(dir.resolve("sub3.json"), SNAPSHOT.replace("\"finishers\":350", "\"finishers\":35"));
        var warehouse = new MarathonSub3(json, snapshot, CLOCK);
        assertThat(warehouse.query(2026, null, 10).available()).isFalse();
        Files.writeString(snapshot, SNAPSHOT);
        assertThat(warehouse.query(2025, null, 10).available()).isFalse();
    }
}
