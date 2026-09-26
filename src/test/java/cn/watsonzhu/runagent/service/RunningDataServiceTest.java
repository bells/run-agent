package cn.watsonzhu.runagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDate;

import cn.watsonzhu.runagent.config.RunningDataProperties;
import cn.watsonzhu.runagent.exception.RunningDataUnavailableException;
import cn.watsonzhu.runagent.model.running.RunningSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

class RunningDataServiceTest {

    @TempDir
    Path temporaryDirectory;

    // 默认测试只读合成 fixture；真实生成数据仅在显式提供环境变量时做额外验证。
    private final RunningDataService service = new RunningDataService(
            new RunningDataProperties(Path.of("src/test/resources/fixtures/activities.json").toAbsolutePath().toString()),
            new ObjectMapper());

    @Test
    void loadsOnlyRunsAndIncludesBothDateBoundaries() {
        RunningSummary summary = service.summarize(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(summary.runCount()).isEqualTo(2);
        assertThat(summary.totalDistanceKm()).isEqualTo(15.0005);
        assertThat(summary.totalDurationSeconds()).isEqualTo(2_730 + 3_723);
    }

    @Test
    void emptyRangeReturnsZeroSummary() {
        RunningSummary summary = service.summarize(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(summary.runCount()).isZero();
        assertThat(summary.totalDistanceKm()).isZero();
        assertThat(summary.totalDurationSeconds()).isZero();
    }

    @Test
    void emptyJsonArrayReturnsZeroSummary() throws Exception {
        Path empty = temporaryDirectory.resolve("empty-activities.json");
        Files.writeString(empty, "[]");
        RunningDataService emptyData = new RunningDataService(
                new RunningDataProperties(empty.toString()), new ObjectMapper());

        RunningSummary summary = emptyData.summarize(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(summary.runCount()).isZero();
        assertThat(summary.totalDistanceKm()).isZero();
        assertThat(summary.totalDurationSeconds()).isZero();
    }

    @Test
    void parsesVariableHourDurations() {
        assertThat(RunningDataService.parseDurationSeconds("0:45:30")).isEqualTo(2_730);
        assertThat(RunningDataService.parseDurationSeconds("1:02:03")).isEqualTo(3_723);
        assertThat(RunningDataService.parseDurationSeconds("12:30:00")).isEqualTo(45_000);
        assertThat(RunningDataService.parseDurationSeconds("2 days, 12:30:00")).isEqualTo(217_800);
        assertThatThrownBy(() -> RunningDataService.parseDurationSeconds("1:61:00"))
                .isInstanceOf(RunningDataUnavailableException.class);
    }

    @Test
    void missingOrUnconfiguredDataIsUnavailable() {
        RunningDataService missing = new RunningDataService(new RunningDataProperties(""), new ObjectMapper());

        assertThatThrownBy(() -> missing.summarize(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .isInstanceOf(RunningDataUnavailableException.class)
                .hasMessage("Running data could not be retrieved");
    }

    @Test
    void malformedJsonIsUnavailableWithoutExposingPath() throws Exception {
        Path broken = temporaryDirectory.resolve("activities.json");
        Files.writeString(broken, "not-json");
        RunningDataService malformed = new RunningDataService(
                new RunningDataProperties(broken.toString()), new ObjectMapper());

        assertThatThrownBy(() -> malformed.summarize(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .isInstanceOf(RunningDataUnavailableException.class)
                .hasMessage("Running data could not be retrieved");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUNNING_DATA_PATH", matches = ".+")
    void configuredRealDataCanBeParsedWithoutExposingActivities() {
        RunningDataService configured = new RunningDataService(
                new RunningDataProperties(System.getenv("RUNNING_DATA_PATH")), new ObjectMapper());

        RunningSummary summary = configured.summarize(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertThat(summary.runCount()).isGreaterThanOrEqualTo(0);
        assertThat(summary.totalDistanceKm()).isGreaterThanOrEqualTo(0);
        assertThat(summary.totalDurationSeconds()).isGreaterThanOrEqualTo(0);
    }
}
