package cn.watsonzhu.runagent.evaluation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import static cn.watsonzhu.runagent.evaluation.EvaluationModels.*;

public final class LiveEvaluationRunner {
    private LiveEvaluationRunner() { }
    public static void main(String[] args) throws Exception {
        var settings = LiveEvaluationSettings.from(System.getenv());
        if (!settings.enabled()) { System.out.println("Live evaluation disabled."); return; }
        if (!settings.ready()) { System.out.println("Live evaluation SKIPPED: explicit AI_GATEWAY_* configuration required."); return; }
        // Validate report identities before any paid request.
        metadata(settings);
        ChatModel target = model(settings, settings.targetModel());
        ChatModel judge = settings.judgeEnabled() ? model(settings, settings.judgeModel()) : null;
        var report = run(Path.of(args[0]), args[1], settings, () -> target, judge, Path.of(".run-agent/evaluation/rag-golden.json"), System.getenv());
        System.out.println("Live evaluation report only: passed=" + report.passedCases() + " failed=" + report.failedCases());
    }
    static ChatModel model(LiveEvaluationSettings settings, String name) {
        var options = OpenAiChatOptions.builder().baseUrl(settings.baseUrl()).apiKey(settings.apiKey()).model(name)
            .maxRetries(0).timeout(settings.timeout()).maxTokens(512);
        if (settings.temperature() != null) options.temperature(settings.temperature());
        return OpenAiChatModel.builder().options(options.build()).build();
    }
    public static Report run(Path output, String version, LiveEvaluationSettings settings, Supplier<ChatModel> targetFactory, ChatModel judge) throws Exception {
        return run(output, version, settings, targetFactory, judge, null, Map.of());
    }
    static Report run(Path output, String version, LiveEvaluationSettings settings, Supplier<ChatModel> targetFactory, ChatModel judge,
                      Path golden, Map<String, String> localEnvironment) throws Exception {
        if (!settings.ready()) throw new IllegalArgumentException("Live evaluation is disabled or unconfigured");
        var metadata = metadata(settings);
        var results = new ArrayList<CaseResult>();
        try (var fixture = new EvaluationFixtures()) {
            for (var item : fixture.cases("tool-cases.json", ToolCase.class)) {
                var recorded = new CopyOnWriteArrayList<ToolCall>();
                try {
                    bounded(settings.timeout(), () -> fixture.agent(new DeadlineModel(targetFactory.get(), settings.timeout()), recorded).agent(null, item.question()));
                    results.add(EvaluationRunner.toolResult(item, List.copyOf(recorded), Evidence.LIVE_MODEL));
                } catch (Exception e) { results.add(EvaluationRunner.toolFailure(item, List.copyOf(recorded), Evidence.LIVE_MODEL, timedOut(e) ? Reason.TIMEOUT : Reason.EXECUTION_FAILED)); }
            }
            if (settings.judgeEnabled()) {
                if (judge == null) throw new IllegalArgumentException("Judge model required when enabled");
                for (var item : fixture.cases("rag-cases.json", RagCase.class)) {
                    if (item.expectedNoContext()) continue;
                    try {
                        var documents = fixture.search.retrieveDocuments("synthetic-evaluation", item.question());
                        var scores = bounded(settings.timeout(), () -> {
                            var target = new DeadlineModel(targetFactory.get(), settings.timeout());
                            String context = String.join("\n", documents.stream().map(org.springframework.ai.document.Document::getText).toList());
                            var answer = ChatClient.create(target).prompt().messages(new SystemMessage("Answer only from this synthetic evidence:\n" + context), new UserMessage(item.question()))
                                .advisors(a -> a.param(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), false)).call().content();
                            if (answer == null || answer.isBlank()) throw new IllegalStateException("No evaluation answer");
                            var request = new EvaluationRequest(item.question(), documents, answer);
                            var builder = ChatClient.builder(new DeadlineModel(judge, settings.timeout()));
                            var relevance = new RelevancyEvaluator(builder).evaluate(request);
                            var groundedness = FactCheckingEvaluator.builder(builder.clone()).build().evaluate(request);
                            return Map.of("answerRelevance", relevance.isPass() ? 1.0 : 0, "groundedness", groundedness.isPass() ? 1.0 : 0);
                        });
                        boolean pass = scores.values().stream().allMatch(v -> v == 1.0);
                        results.add(new CaseResult("judge-" + item.id(), Category.JUDGE, Evidence.LLM_JUDGE, pass ? Status.PASS : Status.FAIL, scores, pass ? Reason.OK : Reason.JUDGE_FAILED));
                    } catch (Exception e) { results.add(EvaluationRunner.failure("judge-" + item.id(), Category.JUDGE, Evidence.LLM_JUDGE, timedOut(e) ? Reason.TIMEOUT : Reason.JUDGE_FAILED)); }
                }
            }
            results.addAll(LocalRagEvaluation.runIfPresent(golden, localEnvironment));
            var identities = new java.util.TreeMap<>(fixture.identities());
            if (golden != null && java.nio.file.Files.isRegularFile(golden)) identities.put("local-rag-golden", LocalRagEvaluation.identity(golden));
            var report = EvaluationReportWriter.report(version, "LIVE", identities, metadata, results);
            EvaluationReportWriter.write(output, report, Path.of(".run-agent/evaluation/baseline.json"));
            return report;
        }
    }
    private static Map<String, String> metadata(LiveEvaluationSettings settings) {
        var data = new java.util.TreeMap<String, String>();
        data.put("targetModel", settings.targetModel()); data.put("provider", settings.provider()); data.put("judgeEnabled", Boolean.toString(settings.judgeEnabled()));
        if (settings.temperature() != null) data.put("temperature", settings.temperature().toString());
        if (settings.judgeEnabled()) { data.put("judgeModel", settings.judgeModel()); data.put("judgeEqualsTarget", Boolean.toString(settings.judgeModel().equals(settings.targetModel()))); }
        EvaluationReportWriter.report("validation", "LIVE", Map.of(), data, List.of());
        return Map.copyOf(data);
    }
    public static <T> T bounded(Duration timeout, Callable<T> action) throws Exception {
        var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("runagent-evaluation").factory());
        var future = executor.submit(action);
        try { return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
        finally { future.cancel(true); executor.shutdownNow(); }
    }
    private static boolean timedOut(Throwable failure) {
        for (Throwable current = failure; current != null && current != current.getCause(); current = current.getCause())
            if (current instanceof TimeoutException) return true;
        return false;
    }
    private static final class DeadlineModel implements ChatModel {
        private final ChatModel delegate;
        private final long deadline;
        private int calls;
        private DeadlineModel(ChatModel delegate, Duration timeout) { this.delegate = delegate; deadline = System.nanoTime() + timeout.toNanos(); }
        @Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() { return delegate.getOptions(); }
        @Override public ChatResponse call(Prompt prompt) {
            check();
            if (++calls > 10) throw new IllegalStateException("Evaluation model call limit exceeded");
            var result = delegate.call(prompt);
            check();
            return result;
        }
        private void check() { if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) throw new IllegalStateException("Evaluation deadline exceeded"); }
    }
}
