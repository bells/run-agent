package cn.watsonzhu.runagent.workflow;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import cn.watsonzhu.runagent.exception.WorkflowException;

/** Per-subscription runtime control; kept in RunnableConfig, outside cloned state and remote metadata. */
public final class WorkflowExecutionControl {
    static final String CONFIG_KEY = "runAgentWorkflowControl";
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final long deadlineNanos;

    public WorkflowExecutionControl(Duration timeout) {
        this.deadlineNanos = System.nanoTime() + timeout.toNanos();
    }

    public void cancel() { cancelled.set(true); }
    public long deadlineNanos() { return deadlineNanos; }

    public void checkActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted() || System.nanoTime() - deadlineNanos >= 0) {
            throw new WorkflowException(WorkflowException.Kind.TIMEOUT);
        }
    }
}
