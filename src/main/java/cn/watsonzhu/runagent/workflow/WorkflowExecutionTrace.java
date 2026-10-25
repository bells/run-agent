package cn.watsonzhu.runagent.workflow;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorkflowExecutionTrace {
    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionTrace.class);

    private WorkflowExecutionTrace() {}

    public static Map<String, Object> run(TrainingAnalysisState state, WorkflowExecutionControl control,
                                          String node, Supplier<Map<String, Object>> action) {
        long started = System.nanoTime();
        log.info("workflowExecutionId={} node={} phase=STARTED", state.executionId(), node);
        String status = "FAILED";
        try {
            control.checkActive();
            Map<String, Object> update = new HashMap<>(action.get());
            // A provider can finish after HTTP cancellation. Never advance to the next node after the deadline.
            control.checkActive();
            update.put("executedNodes", List.of(node));
            status = "SUCCESS";
            return update;
        } finally {
            log.info("workflowExecutionId={} node={} status={} latencyMs={}", state.executionId(), node, status,
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
    }

}
