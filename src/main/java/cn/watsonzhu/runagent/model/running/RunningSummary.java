package cn.watsonzhu.runagent.model.running;

import java.time.LocalDate;

/** 给模型的汇总结果：日期区间两端包含，距离单位为公里，时长单位为秒。 */
public record RunningSummary(
        LocalDate startDate,
        LocalDate endDate,
        int runCount,
        double totalDistanceKm,
        long totalDurationSeconds
) {
}
