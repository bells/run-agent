package cn.watsonzhu.runagent.performance;

import java.math.BigDecimal;
import java.util.List;
import cn.watsonzhu.runagent.config.PerformanceProperties;

public final class CostEstimator {
    private final PerformanceProperties.Pricing pricing;
    public CostEstimator(PerformanceProperties.Pricing pricing) { this.pricing = pricing; }
    public CostEstimate estimate(List<ModelUsageRecord> records) {
        BigDecimal total = BigDecimal.ZERO;
        if (records.isEmpty()) return unknown();
        for (var record : records) {
            var price = pricing.tiers().get(record.tier());
            if (record.usageStatus() != UsageStatus.KNOWN || price == null || !price.known()
                    || record.cacheReadInputTokens() != null && record.cacheReadInputTokens() != 0
                    || record.cacheWriteInputTokens() != null && record.cacheWriteInputTokens() != 0) return unknown();
            total = total.add(BigDecimal.valueOf(record.inputTokens()).multiply(price.inputPerMillion()).movePointLeft(6))
                    .add(BigDecimal.valueOf(record.outputTokens()).multiply(price.outputPerMillion()).movePointLeft(6));
        }
        return new CostEstimate(CostEstimate.Status.KNOWN, total, pricing.currency());
    }
    private CostEstimate unknown() { return new CostEstimate(CostEstimate.Status.UNKNOWN, null, pricing.currency()); }
}
