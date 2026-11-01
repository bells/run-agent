package cn.watsonzhu.runagent.evaluation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class LiveEvaluationRunnerTest {
    @TempDir Path directory;
    @Test void liveAndJudgeAreDisabledWithoutExplicitFlagsAndMissingConfigSkips() {
        var defaults = LiveEvaluationSettings.from(Map.of());
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.judgeEnabled()).isFalse();
        assertThat(defaults.ready()).isFalse();
        assertThat(LiveEvaluationSettings.from(Map.of("LIVE_EVALUATION_ENABLED", "true")).ready()).isFalse();
        assertThatThrownBy(() -> LiveEvaluationSettings.from(Map.of("EVAL_CASE_TIMEOUT_SECONDS", "0"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void liveWithInjectedModelCapturesActualToolArgumentsAndNeverUsesScriptsAsExpectations() throws Exception {
        var settings = LiveEvaluationSettings.from(Map.of("LIVE_EVALUATION_ENABLED", "true", "AI_GATEWAY_BASE_URL", "http://127.0.0.1:1/v1",
            "AI_GATEWAY_API_KEY", "synthetic-secret", "AI_GATEWAY_MODEL", "fake-target"));
        var report = LiveEvaluationRunner.run(directory, "0.8.0-SNAPSHOT", settings, () -> new ScriptedChatModel(List.of()), null);
        assertThat(report.caseCounts()).containsEntry("TOOL", 12);
        assertThat(report.metrics().get("toolSelectionAccuracy")).isCloseTo(1.0 / 12, within(1e-9));
        assertThat(report.failedCases()).isEqualTo(11);
        assertThat(report.model()).containsEntry("targetModel", "fake-target").containsEntry("judgeEnabled", "false");
        assertThat(java.nio.file.Files.readString(directory.resolve("report.json"))).doesNotContain("synthetic-secret", "什么是节奏跑");
    }
    @Test void completedToolSelectionSurvivesFinalModelFailure() throws Exception {
        var settings = LiveEvaluationSettings.from(Map.of("LIVE_EVALUATION_ENABLED", "true", "AI_GATEWAY_BASE_URL", "http://127.0.0.1:1/v1",
            "AI_GATEWAY_API_KEY", "synthetic-secret", "AI_GATEWAY_MODEL", "fake-target"));
        var report = LiveEvaluationRunner.run(directory, "0.8.0-SNAPSHOT", settings, () -> new org.springframework.ai.chat.model.ChatModel() {
            final java.util.concurrent.atomic.AtomicInteger round = new java.util.concurrent.atomic.AtomicInteger();
            @Override public org.springframework.ai.model.tool.ToolCallingChatOptions getOptions() { return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build(); }
            @Override public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
                if (round.getAndIncrement() > 0) throw new IllegalStateException("synthetic-final-failure");
                var arguments = new tools.jackson.databind.json.JsonMapper().readTree("{\"startDate\":\"2026-01-01\",\"endDate\":\"2026-03-31\",\"limit\":3}");
                return new ScriptedChatModel(List.of(new EvaluationModels.ToolCall("getRecentRuns", arguments))).call(prompt);
            }
        }, null);
        var result = report.cases().stream().filter(r -> r.caseId().equals("recent-three")).findFirst().orElseThrow();
        assertThat(result.status()).isEqualTo(EvaluationModels.Status.FAIL);
        assertThat(result.reasonCode()).isEqualTo(EvaluationModels.Reason.EXECUTION_FAILED);
        assertThat(result.metrics()).containsEntry("toolSelectionAccuracy", 1.0).containsEntry("toolArgumentAccuracy", 1.0);
    }
    @Test void correctToolNameWithMissingPrimitiveArgumentStillScoresSelection() throws Exception {
        var settings = LiveEvaluationSettings.from(Map.of("LIVE_EVALUATION_ENABLED", "true", "AI_GATEWAY_BASE_URL", "http://127.0.0.1:1/v1",
            "AI_GATEWAY_API_KEY", "synthetic-secret", "AI_GATEWAY_MODEL", "fake-target"));
        var arguments = new tools.jackson.databind.json.JsonMapper().readTree("{\"startDate\":\"2026-01-01\",\"endDate\":\"2026-03-31\"}");
        var report = LiveEvaluationRunner.run(directory, "0.8.0-SNAPSHOT", settings,
            () -> new ScriptedChatModel(List.of(new EvaluationModels.ToolCall("getRecentRuns", arguments))), null);
        var result = report.cases().stream().filter(r -> r.caseId().equals("recent-three")).findFirst().orElseThrow();
        assertThat(result.status()).isEqualTo(EvaluationModels.Status.FAIL);
        assertThat(result.metrics()).containsEntry("toolSelectionAccuracy", 1.0).containsEntry("toolArgumentAccuracy", 0.0);
    }
    @Test void caseDeadlineReturnsWithoutWaitingForAnUncooperativeModel() {
        var calls = new AtomicInteger();
        long started = System.nanoTime();
        assertThatThrownBy(() -> LiveEvaluationRunner.bounded(Duration.ofMillis(30), () -> {
            calls.incrementAndGet();
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < deadline) { try { Thread.sleep(5); } catch (InterruptedException ignored) { } }
            return "late";
        })).isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void judgeIsExplicitAndReportsSameModelIdentity() throws Exception {
        var settings = LiveEvaluationSettings.from(Map.of("LIVE_EVALUATION_ENABLED", "true", "AI_GATEWAY_BASE_URL", "http://127.0.0.1:1/v1",
            "AI_GATEWAY_API_KEY", "synthetic-secret", "AI_GATEWAY_MODEL", "fake-target", "EVAL_JUDGE_ENABLED", "true"));
        assertThat(settings.judgeModel()).isEqualTo("fake-target");
        assertThat(settings.judgeEnabled()).isTrue();
        var judgeCalls = new AtomicInteger();
        org.springframework.ai.chat.model.ChatModel judge = prompt -> {
            assertThat(prompt.getContents()).doesNotContain("private-book", "synthetic-secret");
            judgeCalls.incrementAndGet();
            return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("YES"))));
        };
        var report = LiveEvaluationRunner.run(directory, "0.8.0-SNAPSHOT", settings, () -> new ScriptedChatModel(List.of()), judge);
        assertThat(judgeCalls.get()).isPositive();
        assertThat(report.model()).containsEntry("judgeEqualsTarget", "true");
        assertThat(report.caseCounts()).containsKey("JUDGE");
    }
}
