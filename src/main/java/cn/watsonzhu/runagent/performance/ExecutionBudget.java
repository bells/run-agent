package cn.watsonzhu.runagent.performance;

import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import cn.watsonzhu.runagent.config.PerformanceProperties;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.multiagent.MultiAgentExecutionControl;

/** One task-wide budget; no resets on handoff and no global execution registry. */
public final class ExecutionBudget {
    public static final String CONFIG_KEY = "runAgentPerformanceBudget";
    private final String executionId;
    private final MultiAgentExecutionControl control;
    private final PerformanceProperties properties;
    private final ModelUsageCollector collector;
    private final long started = System.nanoTime();
    private final Set<BudgetReason> reasons = EnumSet.noneOf(BudgetReason.class);
    private final Map<ModelStage, Duration> stageDurations = new EnumMap<>(ModelStage.class);
    private int modelCalls;
    private boolean softBudgetExceeded;
    public ExecutionBudget(String executionId, MultiAgentExecutionControl control, PerformanceProperties properties) {
        this.executionId = executionId; this.control = control; this.properties = properties;
        this.collector = new ModelUsageCollector(executionId);
    }
    public synchronized void checkActive() {
        try { control.checkActive(); }
        catch (MultiAgentException exception) { reasons.add(BudgetReason.DEADLINE); throw exception; }
    }
    public synchronized void checkCanCall(ModelStage stage) {
        checkActive();
        if (stage != ModelStage.SUPERVISOR_SYNTHESIS && softBudgetExceeded) fail(BudgetReason.SOFT_TOKENS);
        int available = properties.maxModelCalls() - modelCalls;
        if (available < 1 || stage != ModelStage.SUPERVISOR_SYNTHESIS && available <= 1) fail(BudgetReason.MODEL_CALLS);
    }
    public synchronized void reserve(ModelStage stage) {
        checkCanCall(stage);
        modelCalls++;
    }
    private void fail(BudgetReason reason) { reasons.add(reason); throw new MultiAgentException(MultiAgentException.Kind.BUDGET_EXCEEDED); }
    public synchronized boolean stopOptional() {
        if (softBudgetExceeded) return true;
        if (properties.maxModelCalls() - modelCalls <= 1) { reasons.add(BudgetReason.MODEL_CALLS); return true; }
        return false;
    }
    public synchronized void reconcile() {
        if (collector.knownTotalTokens() > properties.softTotalTokens()) {
            softBudgetExceeded = true; reasons.add(BudgetReason.SOFT_TOKENS);
        }
    }
    public synchronized void outputExceeded() { fail(BudgetReason.OUTPUT_TOKENS); }
    public synchronized void stageFinished(ModelStage stage, Duration duration) { stageDurations.merge(stage, duration, Duration::plus); }
    public synchronized Map<ModelStage, Duration> stageDurations() { return Map.copyOf(stageDurations); }
    public synchronized Set<BudgetReason> reasons() { return Set.copyOf(reasons); }
    public synchronized int modelCalls() { return modelCalls; }
    public synchronized boolean softBudgetExceeded() { return softBudgetExceeded; }
    public String executionId() { return executionId; }
    public PerformanceProperties properties() { return properties; }
    public MultiAgentExecutionControl control() { return control; }
    public ModelUsageCollector collector() { return collector; }
    public Duration elapsed() { return Duration.ofNanos(System.nanoTime() - started); }
}
