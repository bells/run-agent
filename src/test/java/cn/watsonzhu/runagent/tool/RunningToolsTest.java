package cn.watsonzhu.runagent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import cn.watsonzhu.runagent.exception.InvalidRunningQueryException;
import cn.watsonzhu.runagent.exception.RunningDataUnavailableException;
import cn.watsonzhu.runagent.model.running.RunningSummary;
import cn.watsonzhu.runagent.service.RunningDataService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;

class RunningToolsTest {

    private final RunningDataService data = mock(RunningDataService.class);
    private final RunningTools tools = new RunningTools(data);

    @Test
    void validDatesReachDataService() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 9, 26);
        RunningSummary expected = new RunningSummary(start, end, 2, 15.0, 3600);
        when(data.summarize(start, end)).thenReturn(expected);

        assertThat(tools.getRunningSummary("2026-01-01", "2026-09-26")).isEqualTo(expected);
        verify(data).summarize(start, end);
    }

    @Test
    void rejectsBadDateOrderFormatAndOversizedRange() {
        assertThatThrownBy(() -> tools.getRunningSummary("2026-09-20", "2026-09-01"))
                .isInstanceOf(InvalidRunningQueryException.class).hasMessageContaining("after endDate");
        assertThatThrownBy(() -> tools.getRunningSummary("2026-9-01", "2026-09-20"))
                .isInstanceOf(InvalidRunningQueryException.class).hasMessageContaining("yyyy-MM-dd");
        assertThatThrownBy(() -> tools.getRunningSummary(null, "2026-09-20"))
                .isInstanceOf(InvalidRunningQueryException.class).hasMessageContaining("startDate");
        assertThatThrownBy(() -> tools.getRunningSummary("2025-01-01", "2026-12-31"))
                .isInstanceOf(InvalidRunningQueryException.class).hasMessageContaining("366");
    }

    @Test
    void hidesDataServiceFailureDetails() {
        when(data.summarize(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .thenThrow(new IllegalStateException("private path and data details"));

        assertThatThrownBy(() -> tools.getRunningSummary("2026-08-01", "2026-08-31"))
                .isInstanceOf(RunningDataUnavailableException.class)
                .hasMessage("Running data could not be retrieved");
    }

    @Test
    void publishesOneDescribedToolSchema() {
        // 与 ChatClient 注册 POJO Tool 时使用同一转换入口，直接检查发给模型的定义。
        var callbacks = ToolCallbacks.from(tools);
        assertThat(callbacks).hasSize(1);
        var definition = callbacks[0].getToolDefinition();
        assertThat(definition.name()).isEqualTo("getRunningSummary");
        assertThat(definition.description()).contains("real historical running statistics");
        assertThat(definition.inputSchema()).contains("startDate", "endDate", "Inclusive", "yyyy-MM-dd");
    }
}
