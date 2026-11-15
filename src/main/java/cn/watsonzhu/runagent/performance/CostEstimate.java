package cn.watsonzhu.runagent.performance;

import java.math.BigDecimal;

public record CostEstimate(Status status, BigDecimal amount, String currency) {
    public enum Status { KNOWN, UNKNOWN }
}
