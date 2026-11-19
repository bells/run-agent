package cn.watsonzhu.runagent.review;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "run-agent.run-review.enabled", havingValue = "true")
public final class RunReviewCoordinator {
    private static final long FAILURE_TTL_NANOS = Duration.ofMinutes(10).toNanos();
    private static final int MAX_FAILURES = 64;
    private static final String GENERATION_FAILED = "REVIEW_GENERATION_FAILED";
    private static final String STORAGE_UNAVAILABLE = "REVIEW_STORAGE_UNAVAILABLE";

    private final RunReviewStore store;
    private final ObjectProvider<RunReviewGenerator> generator;
    private final MeterRegistry metrics;
    private final boolean generationEnabled;
    private final Duration timeout;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("run-review-worker").factory());
    private final ScheduledThreadPoolExecutor deadlines =
            new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("run-review-deadline").factory());
    private final LinkedHashMap<Long, Failure> failures = new LinkedHashMap<>();
    private Job active;

    public RunReviewCoordinator(RunReviewStore store, ObjectProvider<RunReviewGenerator> generator,
            MeterRegistry metrics, @Value("${run-agent.run-review.generation-enabled:false}") boolean generationEnabled,
            @Value("${run-agent.run-review.timeout:25s}") Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Review timeout must be positive");
        this.store = store;
        this.generator = generator;
        this.metrics = metrics;
        this.generationEnabled = generationEnabled;
        this.timeout = timeout;
        deadlines.setRemoveOnCancelPolicy(true);
    }

    public synchronized Reply read(long runId) {
        try {
            var saved = store.load(runId);
            if (saved.isPresent()) return ready(200, saved.orElseThrow());
        } catch (RunReviewStore.Unavailable exception) {
            return reply(503, STORAGE_UNAVAILABLE);
        }
        if (active != null && active.runId() == runId && !active.result().isDone()) {
            return reply(202, "GENERATING");
        }
        var failure = failures.get(runId);
        if (failure != null) {
            if (System.nanoTime() - failure.recordedAt() < FAILURE_TTL_NANOS) {
                return new Reply(200, "FAILED", null, failure.code(), generationEnabled);
            }
            failures.remove(runId);
        }
        return reply(404, "REVIEW_NOT_FOUND");
    }

    public synchronized CompletableFuture<Reply> generate(long runId) {
        try {
            var saved = store.load(runId);
            if (saved.isPresent()) return CompletableFuture.completedFuture(ready(200, saved.orElseThrow()));
        } catch (RunReviewStore.Unavailable exception) {
            return CompletableFuture.completedFuture(reply(503, STORAGE_UNAVAILABLE));
        }
        if (!generationEnabled) return CompletableFuture.completedFuture(reply(503, "REVIEW_GENERATION_DISABLED"));
        if (active != null) {
            var response = active.runId() == runId && !active.result().isDone()
                    ? reply(202, "GENERATING")
                    : reply(429, "REVIEW_BUSY");
            return CompletableFuture.completedFuture(response);
        }

        failures.remove(runId);
        var job = new Job(runId, System.nanoTime() + timeout.toNanos(), Timer.start(metrics), new CompletableFuture<>());
        active = job;
        var deadline = deadlines.schedule(() -> expire(job), timeout.toNanos(), TimeUnit.NANOSECONDS);
        worker.execute(() -> {
            try {
                execute(job);
            } finally {
                deadline.cancel(false);
                synchronized (this) {
                    active = null;
                }
            }
        });
        return job.result();
    }

    private void execute(Job job) {
        try {
            var report = generator.getObject().generate(job.runId());
            synchronized (this) {
                if (job.result().isDone()) return;
                if (System.nanoTime() - job.deadline() >= 0 || report.runId() != job.runId()) {
                    fail(job, 503, GENERATION_FAILED);
                    return;
                }
                store.save(report);
                complete(job, ready(201, report));
            }
        } catch (RunReviewException exception) {
            int status = exception.code() == RunReviewException.Code.RUN_NOT_FOUND ? 404 : 503;
            fail(job, status, exception.code().name());
        } catch (RunReviewStore.Unavailable exception) {
            fail(job, 503, STORAGE_UNAVAILABLE);
        } catch (RuntimeException exception) {
            fail(job, 503, GENERATION_FAILED);
        }
    }

    private void expire(Job job) {
        fail(job, 503, GENERATION_FAILED);
    }

    private synchronized void fail(Job job, int status, String code) {
        if (job.result().isDone()) return;
        if (failures.size() >= MAX_FAILURES) failures.pollFirstEntry();
        failures.put(job.runId(), new Failure(code, System.nanoTime()));
        complete(job, reply(status, code));
    }

    private void complete(Job job, Reply reply) {
        job.sample().stop(metrics.timer("runagent.run_review.generation", "outcome",
                reply.code() == null ? "READY" : reply.code()));
        job.result().complete(reply);
    }

    private Reply ready(int status, RunReviewReport report) {
        return new Reply(status, "READY", report, null, generationEnabled);
    }

    private Reply reply(int status, String code) {
        return status == 202
                ? new Reply(status, "GENERATING", null, null, generationEnabled)
                : new Reply(status, null, null, code, generationEnabled);
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
        deadlines.shutdownNow();
    }

    private record Job(long runId, long deadline, Timer.Sample sample, CompletableFuture<Reply> result) {}

    private record Failure(String code, long recordedAt) {}

    public record Reply(int status, String state, RunReviewReport report, String code, boolean generationEnabled) {}
}
