package cn.watsonzhu.runagent.performance;

import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public final class PerformanceReportWriter {
    private PerformanceReportWriter() { }
    public static void write(Path output,PerformanceBenchmark.Report report) throws java.io.IOException {
        Files.createDirectories(output);
        Files.writeString(output.resolve("report.json"),new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        var b=report.profiles().get(PerformanceMode.BASELINE);var o=report.profiles().get(PerformanceMode.OPTIMIZED);
        var md=new StringBuilder("# RunAgent Performance Benchmark\n\n")
                .append("Type: ").append(report.benchmarkType()).append("; version: ").append(report.applicationVersion())
                .append("; timestamp: ").append(report.runTimestamp()).append("\n\nDataset checksum: ").append(report.datasetChecksum())
                .append("\n\nObserved P95 is a small sample statistic, never a production P95.\n\n")
                .append("Quality gate: ").append(report.qualityGate()).append(". ").append(report.conclusion()).append("\n\n")
                .append("Synthetic prices/usage/delay are fixture values, never provider quotes. UNKNOWN is never zero.\n\n")
                .append("| Metric | BASELINE | OPTIMIZED | Delta |\n| --- | ---: | ---: | ---: |\n");
        row(md,"Success Rate",b.successRate(),o.successRate());row(md,"Delegation Accuracy",b.delegationAccuracy(),o.delegationAccuracy());
        row(md,"Completion Accuracy",b.completionAccuracy(),o.completionAccuracy());row(md,"Tool Contract Accuracy",b.toolContractAccuracy(),o.toolContractAccuracy());
        row(md,"Avg Latency ms",b.averageLatencyMs(),o.averageLatencyMs());row(md,"P50 Latency ms",b.p50LatencyMs(),o.p50LatencyMs());
        row(md,"Observed P95 ms",b.observedP95LatencyMs(),o.observedP95LatencyMs());row(md,"Model Calls / Request",b.averageModelCalls(),o.averageModelCalls());
        row(md,"Input Tokens / Request",b.averageInputTokens(),o.averageInputTokens());row(md,"Output Tokens / Request",b.averageOutputTokens(),o.averageOutputTokens());
        row(md,"Total Tokens / Request",b.averageTotalTokens(),o.averageTotalTokens());row(md,"Estimated Cost / Request",b.averageEstimatedCost(),o.averageEstimatedCost());
        row(md,"Usage Completeness",b.usageCompleteness(),o.usageCompleteness());row(md,"Cost Completeness",b.costCompleteness(),o.costCompleteness());
        md.append("\n## Model routes\n\n| Mode | Stage | Requested tier | Resolved tier | Model | Output cap | Reasoning effort |\n| --- | --- | --- | --- | --- | ---: | --- |\n");
        for(var mode:PerformanceMode.values())for(var stage:ModelStage.values()) {
            var route=report.routes().get(mode).get(stage);
            md.append("| ").append(mode).append(" | ").append(stage).append(" | ").append(route.requestedTier()).append(" | ").append(route.tier()).append(" | ").append(route.model()).append(" | ").append(route.maxOutputTokens()).append(" | ").append(route.reasoningEffort()).append(" |\n");
        }
        md.append("\n## Critical path totals across measured requests\n\n| Mode | Stage | Calls | Stage ms | Model ms | Known tokens | Unknown calls |\n| --- | --- | ---: | ---: | ---: | ---: | ---: |\n");
        for(var mode:PerformanceMode.values())for(var stage:ModelStage.values()) {
            var s=report.profiles().get(mode).stageBreakdown().get(stage);
            md.append("| ").append(mode).append(" | ").append(stage).append(" | ").append(s.calls()).append(" | ").append(s.stageMs()).append(" | ").append(s.modelMs()).append(" | ").append(s.knownTokens()).append(" | ").append(s.unknownCalls()).append(" |\n");
        }
        md.append("\nStage time includes model/tool/retrieval; model time is a subset. Warmups are excluded. Quality checks are deterministic execution contracts; natural-language quality requires further evaluation.\n");
        Files.writeString(output.resolve("report.md"),md);
    }
    private static void row(StringBuilder md,String metric,Number baseline,Number optimized) {
        md.append("| ").append(metric).append(" | ").append(baseline==null?"UNKNOWN":baseline).append(" | ").append(optimized==null?"UNKNOWN":optimized).append(" | ");
        if(baseline==null || optimized==null)md.append("UNKNOWN");else md.append(String.format(Locale.ROOT,"%.6f",optimized.doubleValue()-baseline.doubleValue()));
        md.append(" |\n");
    }
}
