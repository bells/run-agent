package cn.watsonzhu.runagent.performance;

/** Install per graph node, not only around graph.invoke: execution can change threads. */
public final class ExecutionScope implements AutoCloseable {
    private static final ThreadLocal<ExecutionBudget> CURRENT = new ThreadLocal<>();
    private final ExecutionBudget previous;
    private ExecutionScope(ExecutionBudget budget) { previous = CURRENT.get(); CURRENT.set(budget); }
    public static ExecutionScope open(ExecutionBudget budget) { return new ExecutionScope(java.util.Objects.requireNonNull(budget)); }
    public static ExecutionBudget current() { return CURRENT.get(); }
    public static ExecutionBudget require() {
        var budget = current();
        if (budget == null) throw new IllegalStateException("Performance execution scope is required");
        return budget;
    }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
