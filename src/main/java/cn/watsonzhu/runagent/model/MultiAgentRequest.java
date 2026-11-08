package cn.watsonzhu.runagent.model;

import java.time.LocalDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MultiAgentRequest(@NotBlank @Size(max = 4000) String question,
                                LocalDate startDate, LocalDate endDate,
                                @Size(max = 200) String location) { }
