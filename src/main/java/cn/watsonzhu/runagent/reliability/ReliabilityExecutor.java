package cn.watsonzhu.runagent.reliability;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import io.github.resilience4j.bulkhead.*;
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.retry.*;
import io.github.resilience4j.micrometer.tagged.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** One shared dependency registry. Ordering is explicit: retry -> circuit -> semaphore -> actual call. */
@Component
public final class ReliabilityExecutor {
    private static final Duration EXECUTION_MARGIN=Duration.ofMillis(10);
    private final ReliabilityProperties properties;
    private final MeterRegistry meters;
    private final Sleeper sleeper;
    private final FailureClassifier classifier=new FailureClassifier();
    private final Map<Dependency,CircuitBreaker> circuits=new EnumMap<>(Dependency.class);
    private final Map<Dependency,Bulkhead> bulkheads=new EnumMap<>(Dependency.class);
    private final Map<Dependency,Retry> retries=new EnumMap<>(Dependency.class);

    @FunctionalInterface public interface Sleeper { void sleep(Duration delay) throws InterruptedException; }
    @Autowired public ReliabilityExecutor(ReliabilityProperties properties, MeterRegistry meters) {
        this(properties,meters,()->ThreadLocalRandom.current().nextDouble(),duration->TimeUnit.NANOSECONDS.sleep(duration.toNanos()));
    }
    public ReliabilityExecutor(ReliabilityProperties properties, MeterRegistry meters, DoubleSupplier random, Sleeper sleeper) {
        this.properties=properties;this.meters=meters;this.sleeper=sleeper;
        var c=properties.circuitBreaker();
        var circuitRegistry=CircuitBreakerRegistry.of(CircuitBreakerConfig.custom().slidingWindowSize(c.slidingWindowSize())
                .minimumNumberOfCalls(c.minimumNumberOfCalls()).failureRateThreshold(c.failureRateThreshold())
                .waitDurationInOpenState(c.waitDurationInOpenState()).permittedNumberOfCallsInHalfOpenState(c.permittedCallsInHalfOpenState())
                .recordException(e->FailureClassifier.circuitRecordable(classifier.classify(e)))
                .ignoreException(e->!FailureClassifier.circuitRecordable(classifier.classify(e))).build());
        var bulkheadRegistry=BulkheadRegistry.ofDefaults();
        var r=properties.retry();
        var retryRegistry=RetryRegistry.of(RetryConfig.custom().maxAttempts(r.maxAttempts())
                .intervalFunction(attempt->{
                    double base=r.initialDelay().toMillis()*Math.pow(r.multiplier(),attempt-1);
                    double jitter=1+r.jitter()*(2*random.getAsDouble()-1);
                    return Math.max(1L,(long)Math.min(r.maxDelay().toMillis(),base*jitter));
                }).retryOnException(e->FailureClassifier.retryable(classifier.classify(e))).build());
        for(var dependency:Dependency.values()) {
            if(dependency==Dependency.RAG)continue;
            var name=dependency.name().toLowerCase(java.util.Locale.ROOT).replace('_','-');
            circuits.put(dependency,circuitRegistry.circuitBreaker(name));
            int concurrency=switch(dependency) {
                case LLM_GATEWAY -> properties.bulkhead().llm().maxConcurrentCalls();
                case RUNNING_MCP -> properties.bulkhead().runningMcp().maxConcurrentCalls();
                case WEATHER_MCP -> properties.bulkhead().weatherMcp().maxConcurrentCalls();
                default -> throw new IllegalArgumentException("Unsupported dependency");
            };
            bulkheads.put(dependency,bulkheadRegistry.bulkhead(name,BulkheadConfig.custom().maxConcurrentCalls(concurrency).maxWaitDuration(Duration.ZERO).build()));
            retries.put(dependency,retryRegistry.retry(name));
        }
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitRegistry).bindTo(meters);
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheadRegistry).bindTo(meters);
        TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(meters);
    }
    public void degraded(FallbackAction action) {
        if(enabled())meters.counter("runagent.reliability.degraded","outcome","DEGRADED","fallbackAction",action.name()).increment();
    }
    public boolean enabled(){return properties.enabled();}
    public CircuitBreaker circuitBreaker(Dependency dependency) {
        return java.util.Objects.requireNonNull(circuits.get(dependency),"Dependency has no circuit breaker");
    }
    public <T>T execute(Dependency dependency,OperationSafety safety,ReliabilityDeadline deadline,Supplier<T> operation) {
        return execute(dependency,safety,deadline,()->{},operation);
    }
    public <T>T execute(Dependency dependency,OperationSafety safety,ReliabilityDeadline deadline,Runnable preflight,Supplier<T> operation) {
        if(!enabled())return operation.get();
        if(dependency==Dependency.RAG) {
            deadline.checkActive();preflight.run();
            try{return actual(dependency,deadline,operation);}catch(RuntimeException error){throw safe(error);}
        }
        var retry=retries.get(dependency).<T>asyncContext();
        while(true) {
            deadline.checkActive();preflight.run();
            try {
                T result=protectedAttempt(dependency,deadline,preflight,operation);
                retry.onComplete();return result;
            } catch(RuntimeException error) {
                var category=classifier.classify(error);
                if(FailureClassifier.controlFailure(error))throw error;
                if(!safety.retrySafe() || !FailureClassifier.retryable(category))throw error;
                long delayMillis=retry.onError(safe(error));
                if(delayMillis<0)throw error;
                // Eligibility is checked before sleeping and again before the next circuit/permit attempt.
                deadline.checkActive();preflight.run();
                var delay=Duration.ofMillis(delayMillis);
                if(deadline.remaining().compareTo(delay.plus(EXECUTION_MARGIN))<=0)throw new ReliabilityFailure(FailureCategory.TIMEOUT);
                increment("runagent.reliability.retry",dependency,category);
                try{sleeper.sleep(delay);}catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();throw new ReliabilityFailure(FailureCategory.CANCELLED);
                }
            }
        }
    }
    private <T>T protectedAttempt(Dependency dependency,ReliabilityDeadline deadline,Runnable preflight,Supplier<T> operation) {
        var circuit=circuits.get(dependency);
        if(!circuit.tryAcquirePermission()) {
            increment("runagent.reliability.circuit.rejected",dependency,FailureCategory.CIRCUIT_OPEN);
            throw new ReliabilityFailure(FailureCategory.CIRCUIT_OPEN);
        }
        var bulkhead=bulkheads.get(dependency);
        if(!bulkhead.tryAcquirePermission()) {
            circuit.releasePermission();
            increment("runagent.reliability.bulkhead.rejected",dependency,FailureCategory.BULKHEAD_REJECTED);
            throw new ReliabilityFailure(FailureCategory.BULKHEAD_REJECTED);
        }
        long started=System.nanoTime();
        try {
            deadline.checkActive();preflight.run();
            T result=actual(dependency,deadline,operation);
            circuit.onSuccess(System.nanoTime()-started,TimeUnit.NANOSECONDS);return result;
        } catch(RuntimeException error) {
            // A task deadline or cancellation takes priority over a concurrent provider failure.
            try { deadline.checkActive(); }
            catch(RuntimeException inactive) { circuit.releasePermission(); throw inactive; }
            if(FailureClassifier.controlFailure(error)) { circuit.releasePermission(); throw error; }
            var safe=safe(error);
            circuit.onError(System.nanoTime()-started,TimeUnit.NANOSECONDS,safe);
            // Existing budget/deadline domain failures retain their public protocol mapping.
            if(error instanceof cn.watsonzhu.runagent.exception.MultiAgentException || error instanceof cn.watsonzhu.runagent.exception.WorkflowException)throw error;
            throw safe;
        } finally {bulkhead.onComplete();}
    }
    private <T>T actual(Dependency dependency,ReliabilityDeadline deadline,Supplier<T> operation) {
        String outcome="SUCCESS";
        String category="NONE";
        try {
            T result=operation.get();deadline.checkActive();return result;
        } catch(RuntimeException error) {
            outcome="FAILURE";category=classifier.classify(error).name();throw error;
        } finally {
            meters.counter("runagent.reliability.attempts","dependency",dependency.name(),
                    "outcome",outcome,"failureCategory",category).increment();
        }
    }
    private ReliabilityFailure safe(Throwable error) {
        return error instanceof ReliabilityFailure safe?safe:new ReliabilityFailure(classifier.classify(error));
    }
    private void increment(String name,Dependency dependency,FailureCategory category) {
        if(category==null)meters.counter(name,"dependency",dependency.name()).increment();
        else meters.counter(name,"dependency",dependency.name(),"failureCategory",category.name()).increment();
    }
}
