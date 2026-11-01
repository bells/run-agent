package cn.watsonzhu.runagent.evaluation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;
import static cn.watsonzhu.runagent.evaluation.EvaluationModels.*;

public final class EvaluationReportWriter {
    private static final JsonMapper JSON = new JsonMapper();
    private EvaluationReportWriter() { }
    public static Report report(String version, String executionType, Map<String, DatasetIdentity> datasets,
                                Map<String, String> model, List<CaseResult> results) {
        safeId(version);
        if (!List.of("OFFLINE", "LIVE").contains(executionType)) throw new IllegalArgumentException("Invalid execution type");
        datasets.keySet().forEach(EvaluationModels::safeId);
        model.forEach((key, value) -> {
            if (!List.of("targetModel", "provider", "judgeModel", "judgeEqualsTarget", "temperature", "judgeEnabled").contains(key)
                || value == null || !value.matches("[A-Za-z0-9._:/+-]{1,120}") || value.startsWith("sk-"))
                throw new IllegalArgumentException("Unsafe model metadata");
        });
        var counts = new TreeMap<String, Integer>();
        var values = new TreeMap<String, java.util.DoubleSummaryStatistics>();
        for (var result : results) {
            counts.merge(result.category().name(), 1, Integer::sum);
            if (result.status() != Status.SKIPPED && result.status() != Status.UNJUDGED)
                result.metrics().forEach((key, value) -> values.computeIfAbsent(key, ignored -> new java.util.DoubleSummaryStatistics()).accept(value));
        }
        var metrics = new TreeMap<String, Double>();
        values.forEach((key, stats) -> metrics.put(key, stats.getAverage()));
        String identity = JSON.writeValueAsString(new TreeMap<>(datasets));
        return new Report(Instant.now().toString(), version, executionType, "v1", Map.copyOf(datasets),
            checksum(identity.getBytes(StandardCharsets.UTF_8)), Map.copyOf(model), Map.copyOf(counts), Map.copyOf(metrics),
            count(results, Status.PASS), count(results, Status.FAIL), count(results, Status.SKIPPED), count(results, Status.UNJUDGED), List.copyOf(results));
    }
    private static int count(List<CaseResult> results, Status status) { return (int) results.stream().filter(r -> r.status() == status).count(); }
    public static String checksum(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public static void write(Path directory, Report report, Path baseline) throws java.io.IOException {
        Files.createDirectories(directory);
        var tree = JSON.valueToTree(report);
        var markdown = new StringBuilder("# RunAgent Evaluation\n\nExecution: ").append(report.executionType())
            .append("\n\nTimestamp: ").append(report.runTimestamp()).append("\n\nApplication: ").append(report.applicationVersion())
            .append("\n\nDataset checksum: ").append(report.datasetChecksum())
            .append("\n\nPassed: ").append(report.passedCases()).append("; failed: ").append(report.failedCases())
            .append("; skipped: ").append(report.skippedCases()).append("; unjudged: ").append(report.unjudgedCases())
            .append("\n\n| Metric | Value |\n| --- | ---: |\n");
        new TreeMap<>(report.metrics()).forEach((key, value) -> markdown.append("| ").append(key).append(" | ").append(format(value)).append(" |\n"));
        markdown.append("\n## Datasets\n\n");
        new TreeMap<>(report.datasets()).forEach((key, value) -> markdown.append(key).append(": ").append(value.datasetVersion()).append(" / ").append(value.sha256()).append("\n\n"));
        if (!report.model().isEmpty()) markdown.append("Model: ").append(new TreeMap<>(report.model())).append("\n\n");
        var comparison = JSON.createObjectNode();
        comparison.put("status", "NO_BASELINE");
        if (baseline != null && Files.isRegularFile(baseline)) {
            try {
                var old = JSON.readTree(baseline.toFile());
                boolean compatible = old.path("datasetChecksum").asText().equals(report.datasetChecksum())
                    && old.path("executionType").asText().equals(report.executionType())
                    && old.path("metricDefinitionVersion").asText().equals(report.metricDefinitionVersion())
                    && old.path("model").equals(tree.path("model"));
                comparison.put("status", compatible ? "COMPATIBLE" : "BASELINE_INCOMPATIBLE");
                if (compatible) {
                    var delta = comparison.putObject("delta");
                    report.metrics().forEach((key, value) -> {
                        if (old.path("metrics").path(key).isNumber()) {
                            double change = value - old.path("metrics").path(key).asDouble();
                            if (Double.isFinite(change)) {
                                delta.put(key, change);
                                markdown.append(key).append(" delta=").append(format(change)).append("\n\n");
                            }
                        }
                    });
                }
            } catch (RuntimeException e) { comparison.put("status", "BASELINE_INVALID"); }
        }
        ((tools.jackson.databind.node.ObjectNode) tree).set("baselineComparison", comparison);
        markdown.append("Baseline: ").append(comparison.path("status").asText()).append("\n\n## Cases\n\n| ID | Category | Evidence | Status | Reason |\n| --- | --- | --- | --- | --- |\n");
        report.cases().forEach(r -> markdown.append("| ").append(r.caseId()).append(" | ").append(r.category()).append(" | ").append(r.evidence())
            .append(" | ").append(r.status()).append(" | ").append(r.reasonCode()).append(" |\n"));
        Files.writeString(directory.resolve("report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tree) + "\n");
        Files.writeString(directory.resolve("report.md"), markdown.toString());
    }
    private static String format(double value) { return String.format(Locale.ROOT, "%.4f", value); }
}
