package cn.watsonzhu.runagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import cn.watsonzhu.runagent.config.RunningDataProperties;
import cn.watsonzhu.runagent.model.running.DistanceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class V03RunningDataServiceTest {
    @TempDir Path directory;

    @Test
    void recentRunsAreInclusiveSortedLimitedAndCalculatedByJava() throws Exception {
        RunningDataService service = service("""
                [
                  {"run_id":1,"type":"Run","distance":5000,"moving_time":"0:25:00","start_date_local":"2026-08-01 07:00:00","summary_polyline":"private"},
                  {"run_id":2,"type":"Run","distance":0,"moving_time":"0:20:00","start_date_local":"2026-08-31 18:00:00"},
                  {"run_id":3,"type":"Run","distance":10000,"moving_time":"1:00:00","start_date_local":"2026-08-31 19:00:00"},
                  {"run_id":4,"type":"Ride","distance":10000,"moving_time":"0:30:00","start_date_local":"2026-08-31 20:00:00"},
                  {"run_id":5,"type":"Run","distance":3000,"moving_time":"0:18:00","start_date_local":"2026-09-01 06:00:00"}
                ]
                """);
        var runs = service.recentRuns(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 3);
        assertThat(runs).extracting(run -> run.runId()).containsExactly(3L, 2L, 1L);
        assertThat(runs.getFirst().distanceKm()).isEqualTo(10);
        assertThat(runs.getFirst().durationSeconds()).isEqualTo(3600);
        assertThat(runs.getFirst().averagePaceSecondsPerKm()).isEqualTo(360);
        assertThat(runs.get(1).averagePaceSecondsPerKm()).isNull();
        assertThat(service.recentRuns(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 1))
                .hasSize(1);
        assertThat(service.recentRuns(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), 5))
                .isEmpty();
    }

    @Test
    void personalBestUsesFivePercentDistanceWindowAndFastestPace() throws Exception {
        RunningDataService service = service("""
                [
                  {"run_id":1,"type":"Run","distance":5000,"moving_time":"0:26:00","start_date_local":"2026-01-01 07:00:00"},
                  {"run_id":2,"type":"Run","distance":5250,"moving_time":"0:24:00","start_date_local":"2026-01-02 07:00:00"},
                  {"run_id":3,"type":"Run","distance":4749,"moving_time":"0:20:00","start_date_local":"2026-01-03 07:00:00"},
                  {"run_id":4,"type":"Run","distance":10000,"moving_time":"0:50:00","start_date_local":"2026-01-04 07:00:00"},
                  {"run_id":5,"type":"Run","distance":21097.5,"moving_time":"1:40:00","start_date_local":"2026-01-05 07:00:00"},
                  {"run_id":6,"type":"Run","distance":42195,"moving_time":"3:40:00","start_date_local":"2026-01-06 07:00:00"}
                ]
                """);
        var fiveK = service.personalBest(DistanceType.FIVE_K);
        assertThat(fiveK.date()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(fiveK.activityDistanceKm()).isEqualTo(5.25);
        assertThat(fiveK.averagePaceSecondsPerKm()).isEqualTo(274);
        assertThat(fiveK.approximate()).isTrue();
        assertThat(fiveK.calculationBasis()).isEqualTo("WHOLE_ACTIVITY_DISTANCE_MATCH");
        assertThat(service.personalBest(DistanceType.TEN_K).date()).isEqualTo(LocalDate.of(2026, 1, 4));
        assertThat(service.personalBest(DistanceType.HALF_MARATHON).date()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(service.personalBest(DistanceType.MARATHON).date()).isEqualTo(LocalDate.of(2026, 1, 6));
    }

    @Test
    void noMatchingPersonalBestIsExplicit() throws Exception {
        RunningDataService service = service("[]");
        var result = service.personalBest(DistanceType.TEN_K);
        assertThat(result.date()).isNull();
        assertThat(result.calculationBasis()).isEqualTo("NO_MATCHING_ACTIVITY");
    }

    private RunningDataService service(String json) throws Exception {
        Path path = directory.resolve("activities.json");
        Files.writeString(path, json);
        return new RunningDataService(new RunningDataProperties(path.toString()), new ObjectMapper());
    }
}
