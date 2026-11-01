package cn.watsonzhu.runagent.evaluation;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import cn.watsonzhu.runagent.model.TrainingAnalysisRequest;
import cn.watsonzhu.runagent.workflow.WorkflowMode;
import static cn.watsonzhu.runagent.evaluation.EvaluationModels.*;

public final class EvaluationRunner {
    private EvaluationRunner() { }
    public static void main(String[] args) throws Exception {
        var thresholds = new java.util.HashMap<String, Double>();
        for (String name : List.of("toolSelectionAccuracy", "toolArgumentAccuracy", "workflowRouteAccuracy", "recallAtK", "mrr", "precisionAtK")) {
            String configured = System.getProperty("evaluation.threshold." + name);
            if (configured != null) thresholds.put(name, EvaluationMetrics.threshold(configured));
        }
        var report = run(Path.of(args[0]), args[1], thresholds);
        System.out.println("Offline evaluation: passed=" + report.passedCases() + " failed=" + report.failedCases());
    }
    public static Report run(Path output, String version, Map<String, Double> thresholds) throws Exception {
        try (var fixture = new EvaluationFixtures()) { return run(output, version, thresholds, fixture); }
    }
    static Report run(Path output, String version, Map<String, Double> thresholds, EvaluationFixtures fixture) throws Exception {
        var results = new ArrayList<CaseResult>();
            for (var item : fixture.cases("tool-cases.json", ToolCase.class)) {
                var recorded = new java.util.concurrent.CopyOnWriteArrayList<ToolCall>();
                try {
                    fixture.agent(new ScriptedChatModel(fixture.script(item.id())), recorded).agent(null, item.question());
                    results.add(toolResult(item, recorded, Evidence.SCRIPTED_MODEL_CONTRACT));
                } catch (RuntimeException e) { results.add(toolFailure(item, recorded, Evidence.SCRIPTED_MODEL_CONTRACT, Reason.EXECUTION_FAILED)); }
            }
            for (var item : fixture.cases("rag-cases.json", RagCase.class)) {
                results.add(evaluateRetrieval(item, () -> fixture.retrieve(item)));
            }
            var workflow = fixture.workflow();
            for (var item : fixture.cases("workflow-cases.json", WorkflowCase.class)) {
                try {
                    var response = workflow.execute(new TrainingAnalysisRequest(item.question(), WorkflowMode.valueOf(item.mode()), date(item.startDate()), date(item.endDate())));
                    boolean pass = EvaluationMetrics.route(item.expectedNodes(), response.executedNodes());
                    results.add(new CaseResult(item.id(), Category.WORKFLOW, Evidence.DETERMINISTIC_GRAPH, pass ? Status.PASS : Status.FAIL,
                        Map.of("workflowRouteAccuracy", pass ? 1.0 : 0), pass ? Reason.OK : Reason.ROUTE_MISMATCH));
                } catch (RuntimeException e) { results.add(failure(item.id(), Category.WORKFLOW, Evidence.DETERMINISTIC_GRAPH, Reason.EXECUTION_FAILED)); }
            }
            results.add(evaluateCalculation(fixture::runningCalculation));
            var report = EvaluationReportWriter.report(version, "OFFLINE", fixture.identities(), Map.of(), results);
            EvaluationReportWriter.write(output, report, Path.of(".run-agent/evaluation/baseline.json"));
            var gates = new java.util.HashMap<>(Map.of("toolSelectionAccuracy", 1.0, "toolArgumentAccuracy", 1.0, "workflowRouteAccuracy", 1.0,
                "recallAtK", 1.0, "mrr", 1.0, "precisionAtK", 0.8));
            thresholds.forEach((key, value) -> {
                if (!gates.containsKey(key)) throw new IllegalArgumentException("Unknown threshold");
                gates.put(key, EvaluationMetrics.threshold(value.toString()));
            });
            if (report.failedCases() > 0 || gates.entrySet().stream().anyMatch(g -> report.metrics().getOrDefault(g.getKey(), 0.0) + 1e-9 < g.getValue()))
                throw new IllegalStateException("Offline evaluation gate failed");
            return report;
    }
    static CaseResult evaluateRetrieval(RagCase item, java.util.function.Supplier<List<String>> retrieval) {
        try { return retrievalResult(item, retrieval.get(), Evidence.FAKE_EMBEDDING); }
        catch (RuntimeException e) {
            Map<String, Double> metrics = item.expectedNoContext() ? Map.of("noContextAccuracy", 0.0)
                : item.relevantDocumentIds().isEmpty() ? Map.of() : Map.of("recallAtK", 0.0, "precisionAtK", 0.0, "mrr", 0.0);
            return new CaseResult(item.id(), Category.RAG, Evidence.FAKE_EMBEDDING, Status.FAIL, metrics, Reason.EXECUTION_FAILED);
        }
    }
    static CaseResult evaluateCalculation(java.util.function.BooleanSupplier calculation) {
        boolean pass;
        try { pass = calculation.getAsBoolean(); } catch (RuntimeException e) { pass = false; }
        return new CaseResult("running-calculation", Category.RUNNING_CALCULATION, Evidence.SCRIPTED_MODEL_CONTRACT,
            pass ? Status.PASS : Status.FAIL, Map.of("runningCalculationAccuracy", pass ? 1.0 : 0), pass ? Reason.OK : Reason.EXECUTION_FAILED);
    }
    static CaseResult toolFailure(ToolCase item, List<ToolCall> recorded, Evidence evidence, Reason reason) {
        if (recorded.isEmpty()) return failure(item.id(), Category.TOOL, evidence, reason);
        var scored = toolResult(item, recorded, evidence);
        return new CaseResult(item.id(), Category.TOOL, evidence, Status.FAIL, scored.metrics(), reason);
    }
    static CaseResult toolResult(ToolCase item, List<ToolCall> recorded, Evidence evidence) {
        var score = EvaluationMetrics.tools(item, recorded);
        return new CaseResult(item.id(), Category.TOOL, evidence, score.arguments() ? Status.PASS : Status.FAIL,
            Map.of("toolSelectionAccuracy", score.selection() ? 1.0 : 0, "toolArgumentAccuracy", score.arguments() ? 1.0 : 0),
            !score.selection() ? Reason.TOOL_MISMATCH : !score.arguments() ? Reason.ARGUMENT_MISMATCH : Reason.OK);
    }
    static CaseResult retrievalResult(RagCase item, List<String> ids, Evidence evidence) {
        safeId(item.id());
        EvaluationMetrics.threshold(Double.toString(item.minRecall()));
        EvaluationMetrics.threshold(Double.toString(item.minReciprocalRank()));
        var score = EvaluationMetrics.retrieval(item.relevantDocumentIds(), ids, item.k());
        if (item.expectedNoContext()) {
            if (!item.relevantDocumentIds().isEmpty()) throw new IllegalArgumentException("Conflicting retrieval labels");
            return new CaseResult(item.id(), Category.RAG, evidence, ids.isEmpty() ? Status.PASS : Status.FAIL,
                Map.of("noContextAccuracy", ids.isEmpty() ? 1.0 : 0), ids.isEmpty() ? Reason.NO_CONTEXT : Reason.RETRIEVAL_BELOW_THRESHOLD);
        }
        boolean pass = score.recall() >= item.minRecall() && score.reciprocalRank() >= item.minReciprocalRank();
        return new CaseResult(item.id(), Category.RAG, evidence, !score.judged() ? Status.UNJUDGED : pass ? Status.PASS : Status.FAIL,
            score.judged() ? Map.of("recallAtK", score.recall(), "precisionAtK", score.precision(), "mrr", score.reciprocalRank()) : Map.of(),
            !score.judged() ? Reason.UNJUDGED_LABELS : pass ? Reason.OK : Reason.RETRIEVAL_BELOW_THRESHOLD);
    }
    static CaseResult failure(String id, Category category, Evidence evidence, Reason reason) {
        var metrics = category == Category.TOOL ? Map.of("toolSelectionAccuracy", 0.0, "toolArgumentAccuracy", 0.0)
            : category == Category.WORKFLOW ? Map.of("workflowRouteAccuracy", 0.0) : Map.<String, Double>of();
        return new CaseResult(id, category, evidence, Status.FAIL, metrics, reason);
    }
    private static LocalDate date(String value) { return value == null ? null : LocalDate.parse(value); }
}
