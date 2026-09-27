package cn.watsonzhu.runagent.model.running;

import java.time.LocalDate;

public record PersonalBest(DistanceType distanceType, LocalDate date, double activityDistanceKm,
                           long durationSeconds, Integer averagePaceSecondsPerKm,
                           boolean approximate, String calculationBasis) {
}
