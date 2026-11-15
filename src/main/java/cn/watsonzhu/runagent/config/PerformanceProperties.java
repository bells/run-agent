package cn.watsonzhu.runagent.config;

import java.math.BigDecimal;
import java.util.Map;
import cn.watsonzhu.runagent.performance.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("run-agent.performance")
public record PerformanceProperties(
        @DefaultValue("BASELINE") PerformanceMode mode,
        @DefaultValue("16") int maxModelCalls,
        @DefaultValue("1500") int maxOutputTokensPerCall,
        @DefaultValue("30000") long softTotalTokens,
        Map<ModelTier, ModelConfig> models,
        Map<ModelStage, Integer> stageOutputLimits,
        @DefaultValue Context context,
        @DefaultValue Pricing pricing,
        Map<ModelStage, ModelReasoningEffort> stageReasoningEffort) {
    public PerformanceProperties {
        if (mode == null || maxModelCalls < 2 || maxModelCalls > 1000 || maxOutputTokensPerCall < 1
                || maxOutputTokensPerCall > 100000 || softTotalTokens < 1)
            throw new IllegalArgumentException("Invalid performance budget configuration");
        models = models == null ? Map.of() : Map.copyOf(models);
        stageOutputLimits = stageOutputLimits == null ? Map.of() : Map.copyOf(stageOutputLimits);
        stageReasoningEffort = stageReasoningEffort == null ? Map.of() : Map.copyOf(stageReasoningEffort);
        if (stageOutputLimits.values().stream().anyMatch(v -> v < 1 || v > 100000))
            throw new IllegalArgumentException("Stage output limits must be positive and at most 100000");
        context = context == null ? new Context(600, 9000) : context;
        pricing = pricing == null ? new Pricing("USD", Map.of()) : pricing;
    }
    public record ModelConfig(String name) {
        public ModelConfig {
            name = name == null ? "" : name.strip();
            if (!name.isEmpty() && !name.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,199}"))
                throw new IllegalArgumentException("Invalid configured model identity");
        }
    }
    public record Context(@DefaultValue("600") int routingReportChars, @DefaultValue("9000") int knowledgeEvidenceChars) {
        public Context {
            if (routingReportChars < 1 || routingReportChars > 5000 || knowledgeEvidenceChars < 50 || knowledgeEvidenceChars > 12000)
                throw new IllegalArgumentException("Invalid performance context bounds");
        }
    }
    public record Pricing(@DefaultValue("USD") String currency, Map<ModelTier, Price> tiers) {
        public Pricing {
            if (currency == null || !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Invalid pricing currency");
            tiers = tiers == null ? Map.of() : Map.copyOf(tiers);
        }
    }
    public record Price(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
        public boolean known() { return inputPerMillion != null && outputPerMillion != null
                && inputPerMillion.signum() >= 0 && outputPerMillion.signum() >= 0; }
    }
}
