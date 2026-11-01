package cn.watsonzhu.runagent.evaluation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import static cn.watsonzhu.runagent.evaluation.EvaluationModels.*;

public final class EvaluationMetrics {
    private EvaluationMetrics() { }
    public static ToolScore tools(ToolCase expected, List<ToolCall> actual) {
        boolean selection = expected.allowedToolSets().stream().anyMatch(path -> matches(path, actual, expected.ordered(), false));
        boolean arguments = expected.allowedToolSets().stream().anyMatch(path -> matches(path, actual, expected.ordered(), true));
        return new ToolScore(selection, arguments);
    }
    private static boolean matches(List<ToolCall> expected, List<ToolCall> actual, boolean ordered, boolean arguments) {
        if (expected.size() != actual.size()) return false;
        var remaining = new java.util.ArrayList<>(actual);
        for (var call : expected) {
            int match = -1;
            for (int i = 0; i < remaining.size(); i++) {
                var candidate = remaining.get(i);
                if (call.name().equals(candidate.name()) && (!arguments || equivalent(call.arguments(), candidate.arguments()))) { match = i; break; }
                if (ordered) break;
            }
            if (match < 0) return false;
            remaining.remove(match);
        }
        return true;
    }
    private static boolean equivalent(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return new BigDecimal(a.asText()).compareTo(new BigDecimal(b.asText())) == 0;
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            for (var key : a.propertyNames()) if (!b.has(key) || !equivalent(a.get(key), b.get(key))) return false;
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!equivalent(a.get(i), b.get(i))) return false;
            return true;
        }
        if (a.isTextual() && b.isTextual() && a.asText().matches("\\d{4}-\\d{2}-\\d{2}")) {
            try { return LocalDate.parse(a.asText()).equals(LocalDate.parse(b.asText())); }
            catch (java.time.DateTimeException e) { return false; }
        }
        return a.equals(b);
    }
    public static RetrievalScore retrieval(Set<String> relevant, List<String> ranked, int k) {
        if (k < 1) throw new IllegalArgumentException("K must be positive");
        if (relevant.isEmpty()) return new RetrievalScore(false, 0, 0, 0);
        int hits = 0;
        double rr = 0;
        var seen = new HashSet<String>();
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            String id = ranked.get(i);
            if (seen.add(id) && relevant.contains(id)) {
                hits++;
                if (rr == 0) rr = 1.0 / (i + 1);
            }
        }
        return new RetrievalScore(true, (double) hits / relevant.size(), (double) hits / k, rr);
    }
    public static boolean route(List<String> expected, List<String> actual) { return expected.equals(actual); }
    public static double threshold(String value) {
        double result = Double.parseDouble(value);
        if (!Double.isFinite(result) || result < 0 || result > 1) throw new IllegalArgumentException("Threshold must be in [0,1]");
        return result;
    }
}
