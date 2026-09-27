package cn.watsonzhu.runagent.model.running;

import java.time.LocalDate;

public record RunningActivitySummary(long runId, LocalDate date, double distanceKm,
                                     long durationSeconds, Integer averagePaceSecondsPerKm) {
}
