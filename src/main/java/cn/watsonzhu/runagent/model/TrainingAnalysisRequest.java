package cn.watsonzhu.runagent.model;

import java.time.LocalDate;
import cn.watsonzhu.runagent.workflow.WorkflowMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TrainingAnalysisRequest(
        @NotBlank @Size(max = 4000) String question,
        @NotNull WorkflowMode mode,
        LocalDate startDate,
        LocalDate endDate) {
}
