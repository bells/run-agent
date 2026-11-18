package cn.watsonzhu.runagent.multiagent;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import cn.watsonzhu.runagent.exception.MultiAgentException;

/** Local runtime control travels only through RunnableConfig and Host ToolContext. */
public final class MultiAgentExecutionControl {
    public static final String CONFIG_KEY = "runAgentMultiAgentControl";
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger delegations = new AtomicInteger();
    private final long deadlineNanos;

    public MultiAgentExecutionControl(Duration timeout) { deadlineNanos = System.nanoTime() + timeout.toNanos(); }
    public Duration remaining() { return Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime())); }
    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }
    public int delegationCount() { return delegations.get(); }
    void delegated() { delegations.incrementAndGet(); }
    public void checkActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted() || System.nanoTime() - deadlineNanos >= 0)
            throw new MultiAgentException(MultiAgentException.Kind.TIMEOUT);
    }
}
