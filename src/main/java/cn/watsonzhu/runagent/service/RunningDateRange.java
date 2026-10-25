package cn.watsonzhu.runagent.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import cn.watsonzhu.runagent.exception.InvalidRunningQueryException;

public record RunningDateRange(LocalDate start, LocalDate end) {
    public RunningDateRange {
        if (start == null || end == null) {
            throw new InvalidRunningQueryException("startDate and endDate are required");
        }
        if (start.isAfter(end)) {
            throw new InvalidRunningQueryException("startDate must not be after endDate");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > 366) {
            throw new InvalidRunningQueryException("Date range must not exceed 366 inclusive days");
        }
    }
}
