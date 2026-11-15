package cn.watsonzhu.runagent.performance;

import java.util.List;
import cn.watsonzhu.runagent.config.PerformanceProperties;
import org.springframework.ai.document.Document;

public final class ContextBudgetPolicy {
    private final PerformanceProperties properties;
    public ContextBudgetPolicy(PerformanceProperties properties) { this.properties = properties; }
    public String routingReport(String report) { return properties.mode() == PerformanceMode.BASELINE ? report : bounded(report, properties.context().routingReportChars()); }
    public String synthesisReport(String report) { return report; }
    public String knowledgeEvidence(List<Document> documents) {
        int limit = properties.mode() == PerformanceMode.BASELINE ? 12000 : properties.context().knowledgeEvidenceChars();
        var evidence = new StringBuilder();
        for (var document : documents) {
            if (document.getText() == null || document.getText().isBlank()) continue;
            String title = String.valueOf(document.getMetadata().getOrDefault("sourceTitle", "Unknown source"));
            String header = "Source: " + bounded(title, Math.min(200, limit / 2)) + "\n";
            int remaining = limit - evidence.length();
            if (remaining <= header.length()) break;
            String text = bounded(document.getText(), remaining - header.length() - 1);
            if (text.isBlank()) break;
            evidence.append(header).append(text).append('\n');
            if (text.length() < document.getText().length()) break;
        }
        return evidence.toString();
    }
    public static String bounded(String value, int chars) {
        if (value.length() <= chars) return value;
        int end = chars;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
}
