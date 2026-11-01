package cn.watsonzhu.runagent.evaluation;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public record LiveEvaluationSettings(boolean enabled, boolean judgeEnabled, String baseUrl, String apiKey,
                                     String targetModel, String judgeModel, String provider, Double temperature, Duration timeout) {
    public static LiveEvaluationSettings from(Map<String, String> environment) {
        boolean enabled = Boolean.parseBoolean(environment.getOrDefault("LIVE_EVALUATION_ENABLED", "false"));
        boolean judge = Boolean.parseBoolean(environment.getOrDefault("EVAL_JUDGE_ENABLED", environment.getOrDefault("JUDGE_ENABLED", "false")));
        String target = environment.getOrDefault("AI_GATEWAY_MODEL", "");
        String judgeModel = environment.getOrDefault("EVAL_JUDGE_MODEL", target);
        String baseUrl = environment.getOrDefault("AI_GATEWAY_BASE_URL", "");
        if (!baseUrl.isBlank()) {
            URI uri;
            try { uri = URI.create(baseUrl); } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid evaluation endpoint"); }
            if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("Invalid evaluation endpoint");
        }
        int seconds;
        Double temperature;
        try {
            seconds = Integer.parseInt(environment.getOrDefault("EVAL_CASE_TIMEOUT_SECONDS", "30"));
            temperature = environment.containsKey("EVAL_TEMPERATURE") ? Double.valueOf(environment.get("EVAL_TEMPERATURE")) : null;
        } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid evaluation execution settings"); }
        if (seconds < 1 || seconds > 300 || temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2))
            throw new IllegalArgumentException("Invalid evaluation execution settings");
        return new LiveEvaluationSettings(enabled, enabled && judge, baseUrl, environment.getOrDefault("AI_GATEWAY_API_KEY", ""),
            target, judgeModel, environment.getOrDefault("EVAL_PROVIDER", "openai-compatible"), temperature, Duration.ofSeconds(seconds));
    }
    public boolean ready() { return enabled && !baseUrl.isBlank() && !apiKey.isBlank() && !targetModel.isBlank(); }
    @Override public String toString() { return "LiveEvaluationSettings[enabled=" + enabled + ", judgeEnabled=" + judgeEnabled + "]"; }
}
