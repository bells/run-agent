package cn.watsonzhu.runagent.reliability.recovery;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import cn.watsonzhu.runagent.model.TrainingAnalysisRequest;
import cn.watsonzhu.runagent.model.TrainingAnalysisResponse;
import cn.watsonzhu.runagent.workflow.KnowledgeEvidence;
import cn.watsonzhu.runagent.workflow.TrainingAnalysisState;
import cn.watsonzhu.runagent.workflow.WorkflowExecutionControl;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.springframework.beans.factory.annotation.Autowired;
import cn.watsonzhu.runagent.reliability.ReliabilityProperties;
import org.springframework.stereotype.Service;

/** JVM-lifetime recovery. Sensitive context is ephemeral and never enters the official saver. */
@Service
public final class WorkflowRecoveryService {
    private final boolean enabled;
    private final int capacity;
    private final long retentionNanos;
    private final MemorySaver saver = new MemorySaver();
    private final MeterRegistry meters;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private Runner runner;
    private Duration timeout;

    @Autowired
    public WorkflowRecoveryService(ReliabilityProperties properties, MeterRegistry meters) {
        this(properties.checkpoint().enabled(), 64, Duration.ofMinutes(30), meters);
    }

    public WorkflowRecoveryService(boolean enabled) {
        this(enabled, 64, Duration.ofMinutes(30));
    }

    public WorkflowRecoveryService(boolean enabled, int capacity, Duration retention) {
        this(enabled, capacity, retention, new SimpleMeterRegistry());
    }

    public WorkflowRecoveryService(boolean enabled, int capacity, Duration retention, MeterRegistry meters) {
        if (capacity < 1 || retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("Workflow recovery capacity and retention must be positive");
        }
        this.meters = java.util.Objects.requireNonNull(meters, "Meter registry is required");
        this.enabled = enabled;
        this.capacity = capacity;
        this.retentionNanos = retention.toNanos();
    }

    public boolean enabled() { return enabled; }
    public MemorySaver saver() { return saver; }

    /** Called once by the owning compiled workflow; no graph is compiled per request. */
    public synchronized void bind(Runner runner, Duration timeout) {
        if (this.runner != null) throw new IllegalStateException("Workflow recovery is already bound");
        this.runner = runner;
        this.timeout = timeout;
    }

    public synchronized String create(TrainingAnalysisRequest request) {
        requireEnabled();
        expire();
        if (sessions.size() >= capacity) throw new IllegalStateException("Workflow recovery capacity reached");
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Session(request));
        return id;
    }

    public TrainingAnalysisResponse run(String executionId) {
        return run(executionId, null, false);
    }

    /** A deliberate new invocation gets one fresh deadline, shared by every remaining node and attempt. */
    public TrainingAnalysisResponse resume(String executionId) {
        return run(executionId, null, true);
    }

    public TrainingAnalysisResponse run(String executionId, WorkflowExecutionControl control) {
        return run(executionId, control, false);
    }

    private TrainingAnalysisResponse run(String id, WorkflowExecutionControl control, boolean resume) {
        boolean success = false;
        try {
            var result = invoke(id, control == null ? new WorkflowExecutionControl(timeout()) : control, resume);
            success = true;
            return result;
        } finally {
            meters.counter("runagent.reliability.recovery", "phase", resume ? "RESUME" : "START",
                    "outcome", success ? "SUCCESS" : "FAILURE").increment();
        }
    }

    private TrainingAnalysisResponse invoke(String id, WorkflowExecutionControl control, boolean resume) {
        final Session session;
        synchronized (this) {
            session = session(id);
            if (session.active) throw new IllegalStateException("Workflow execution is already active");
            if (session.result != null) return session.result;
            if (resume && !session.started) throw new IllegalStateException("Workflow execution has not started");
            if (!resume && session.started) throw new IllegalStateException("Use resume for a started workflow execution");
            control.checkActive();
            session.active = true;
            session.started = true;
        }
        try {
            control.checkActive();
            var result = runner.invoke(id, session.request, control, resume);
            synchronized (this) { session.result = result; }
            return result;
        } finally {
            synchronized (this) {
                session.active = false;
                session.touchedNanos = System.nanoTime();
                // Admission can succeed just before the deadline expires; no checkpoint means there is nothing to resume.
                if (session.result == null && saver.get(config(id)).isEmpty()) session.started = false;
            }
        }
    }

    /** Only safe state is passed to LangGraph4j; raw evidence lives in this bounded context until release. */
    public synchronized TrainingAnalysisState hydrate(TrainingAnalysisState state) {
        var session = session(state.executionId());
        Map<String, Object> data = new HashMap<>(state.data());
        data.put("question", session.request.question());
        if (!session.evidence.isEmpty()) data.put("knowledgeEvidence", session.evidence);
        if (session.analysis != null) data.put("analysis", session.analysis);
        return new TrainingAnalysisState(data);
    }

    public synchronized Map<String, Object> safeUpdate(String id, Map<String, Object> update) {
        var session = session(id);
        var safe = new HashMap<>(update);
        Object evidence = safe.remove("knowledgeEvidence");
        if (evidence instanceof List<?> values) {
            session.evidence = values.stream().map(KnowledgeEvidence.class::cast).toList();
        }
        Object analysis = safe.remove("analysis");
        if (analysis instanceof String content) {
            session.analysis = content;
            safe.put("analysisStatus", content.isBlank() ? "EMPTY" : "PRODUCED");
        }
        // An allowlist guards future node changes from silently persisting arbitrary model or tool payloads.
        if (!List.of("runningSummary", "executedNodes", "analysisStatus").containsAll(safe.keySet())) {
            throw new IllegalStateException("Unsupported workflow checkpoint update");
        }
        return safe;
    }

    public synchronized TrainingAnalysisResponse response(TrainingAnalysisState state) {
        return new TrainingAnalysisResponse(state.executionId(), state.mode(), state.executedNodes(),
                session(state.executionId()).analysis);
    }

    public synchronized List<Map<String, Object>> checkpointStates(String id) {
        session(id);
        return saver.list(config(id)).stream().map(checkpoint -> {
            var snapshot = new HashMap<>(checkpoint.getState());
            if (snapshot.get("executedNodes") instanceof List<?> nodes) {
                snapshot.put("executedNodes", List.copyOf(nodes));
            }
            return Map.copyOf(snapshot);
        }).toList();
    }

    public synchronized void forget(String id) {
        var session = session(id);
        if (session.active) throw new IllegalStateException("Workflow execution is already active");
        release(id);
    }

    private Duration timeout() {
        synchronized (this) {
            requireEnabled();
            if (runner == null) throw new IllegalStateException("Workflow recovery is not bound");
            return timeout;
        }
    }

    private Session session(String id) {
        requireEnabled();
        expire();
        var session = sessions.get(id);
        if (session == null) throw new IllegalArgumentException("Workflow recovery execution not found or expired");
        return session;
    }

    private void requireEnabled() {
        if (!enabled) throw new IllegalStateException("Workflow checkpoint recovery is disabled");
    }

    private void expire() {
        long now = System.nanoTime();
        var expired = sessions.entrySet().stream()
                .filter(entry -> !entry.getValue().active && now - entry.getValue().touchedNanos >= retentionNanos)
                .map(Map.Entry::getKey).toList();
        expired.forEach(this::release);
    }

    private void release(String id) {
        try { saver.release(config(id)); }
        catch (Exception exception) { throw new IllegalStateException("Workflow checkpoint release failed"); }
        sessions.remove(id);
    }

    private static RunnableConfig config(String id) { return RunnableConfig.builder().threadId(id).build(); }

    @FunctionalInterface
    public interface Runner {
        TrainingAnalysisResponse invoke(String executionId, TrainingAnalysisRequest request, WorkflowExecutionControl control, boolean resume);
    }

    private static final class Session {
        final TrainingAnalysisRequest request;
        List<KnowledgeEvidence> evidence = List.of();
        String analysis;
        TrainingAnalysisResponse result;
        boolean active;
        boolean started;
        long touchedNanos = System.nanoTime();
        Session(TrainingAnalysisRequest request) { this.request = request; }
    }
}
