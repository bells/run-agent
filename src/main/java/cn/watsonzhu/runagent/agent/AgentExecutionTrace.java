package cn.watsonzhu.runagent.agent;

import java.util.concurrent.atomic.AtomicInteger;

public final class AgentExecutionTrace {
    public static final String CONTEXT_KEY = "agentExecution";

    private final String executionId;
    private final AtomicInteger toolCallCounter = new AtomicInteger();

    public AgentExecutionTrace(String executionId) {
        this.executionId = executionId;
    }

    public String executionId() {
        return executionId;
    }

    public int nextToolCall() {
        return toolCallCounter.incrementAndGet();
    }

    public int toolCallCount() {
        return toolCallCounter.get();
    }
}
