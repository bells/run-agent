package cn.watsonzhu.runagent.marathon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static cn.watsonzhu.runagent.marathon.MarathonData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarathonCalendarTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path directory;

    @Test
    void findsChongqingHalfByCityMonthDistanceAndAliasUsingRaceTimezone() {
        var calendar = new MarathonCalendar(JSON, null, CLOCK);
        var result = calendar.query("重庆市", "2026-12", "半马", 10, "重庆半马", false);

        assertThat(result.status()).isEqualTo(Status.READY);
        assertThat(result.events()).singleElement().satisfies(entry -> {
            assertThat(entry.event().eventId()).isEqualTo("chongqing_half_2026");
            assertThat(entry.event().raceDate()).isEqualTo(LocalDate.parse("2026-12-27"));
            assertThat(entry.daysUntilRace()).isEqualTo(36);
        });
        assertThat(calendar.query("重庆", "2026-12", "MARATHON", 10, null, false).events()).isEmpty();
        assertThat(calendar.query("广东省", "2026-12", null, null, null, false).events())
                .extracting(entry -> entry.event().eventId()).contains("guangzhou_marathon_2026");
        assertThat(calendar.query(null, "2026-13", null, null, null, false).status()).isEqualTo(Status.INVALID);
    }

    @Test
    void parsesSnapshotAndRetainsLastValidVersionWhenReplacementIsInvalid() throws Exception {
        var path = directory.resolve("calendar.json");
        var calendar = new MarathonCalendar(JSON, path, CLOCK);
        try (var input = getClass().getResourceAsStream("/marathon/synthetic_calendar.json")) {
            Files.copy(input, path);
        }
        var mounted = calendar.current();
        assertThat(mounted.events()).singleElement().satisfies(event -> assertThat(event.eventId()).isEqualTo("fixture_half_2026"));
        assertThat(calendar.query(null, "2026-12", null, null, null, false).events()).singleElement()
                .satisfies(entry -> assertThat(entry.daysUntilRegistrationDeadline()).isEqualTo(-1));
        assertThat(calendar.query(null, "2027-02", null, null, null, false).status()).isEqualTo(Status.PARTIAL);
        assertThat(calendar.query(null, "2027-03", null, null, null, false).status()).isEqualTo(Status.NO_MATCH);
        Files.writeString(path, "{\"schema_version\":2}");
        assertThat(calendar.current()).isSameAs(mounted);
        Files.delete(path);
        assertThat(calendar.current()).isSameAs(mounted);
    }

    @Test
    void validatesKeyAndOfficialResultTime() {
        assertThatThrownBy(() -> new Series("../example", "示例赛事", java.util.List.of(), java.util.List.of(),
                java.util.List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rank(1, "示例选手", null, "00:00:00", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rank(1, "示例选手", null, "01:65:00", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
