package cn.watsonzhu.runagent.reliability;

import java.time.Duration;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import static org.assertj.core.api.Assertions.*;

class ReliabilityCoreTest {
    static final ReliabilityDeadline ACTIVE = new ReliabilityDeadline() {
        public void checkActive() { }
        public Duration remaining() { return Duration.ofSeconds(30); }
    };
    static ReliabilityProperties properties(boolean enabled, int calls, int concurrency) {
        return new ReliabilityProperties(enabled, new ReliabilityProperties.Retry(2,Duration.ofMillis(20),2,Duration.ofSeconds(1),0),
                new ReliabilityProperties.CircuitBreaker(10,calls,50,Duration.ofSeconds(30),2),
                new ReliabilityProperties.Bulkhead(new ReliabilityProperties.Limits(concurrency),null,null),null);
    }
    static RuntimeException http(int status) {
        return WebClientResponseException.create(status,"PRIVATE_BODY",HttpHeaders.EMPTY,"PRIVATE_BODY".getBytes(),null);
    }
    static ReliabilityExecutor executor(boolean enabled) {
        return new ReliabilityExecutor(properties(enabled,5,2),new SimpleMeterRegistry(),()->0.5,d->{});
    }
    @Test void transientFailureRetriesOnceAndExposesOnlySafeCategory() {
        var e=executor(true); var calls=new AtomicInteger();
        assertThat(e.execute(Dependency.LLM_GATEWAY,OperationSafety.IDEMPOTENT,ACTIVE,()->{
            if(calls.incrementAndGet()==1)throw http(429); return "ok";
        })).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
        assertThatThrownBy(()->e.execute(Dependency.WEATHER_MCP,OperationSafety.READ_ONLY,ACTIVE,()->{throw new RuntimeException("PRIVATE_BODY",http(503));}))
                .isInstanceOfSatisfying(ReliabilityFailure.class,f->{assertThat(f.category()).isEqualTo(FailureCategory.TRANSIENT); assertThat(f.getCause()).isNull();})
                .hasMessageNotContaining("PRIVATE");
    }
    @ParameterizedTest @ValueSource(ints={400,401,403})
    void permanentHttpFailureIsOnceAndNeverRecordsCircuit(int status) {
        var e=executor(true); var calls=new AtomicInteger();
        assertThatThrownBy(()->e.execute(Dependency.LLM_GATEWAY,OperationSafety.IDEMPOTENT,ACTIVE,()->{calls.incrementAndGet();throw http(status);}))
                .isInstanceOfSatisfying(ReliabilityFailure.class,f->assertThat(f.category()).isEqualTo(FailureCategory.PERMANENT));
        assertThat(calls.get()).isOne();
        assertThat(e.circuitBreaker(Dependency.LLM_GATEWAY).getMetrics().getNumberOfBufferedCalls()).isZero();
    }
    @Test void disabledUnknownNonIdempotentAndRagNeverRetry() {
        var calls=new AtomicInteger();
        for(var dep:Dependency.values()) assertThatThrownBy(()->executor(false).execute(dep,OperationSafety.READ_ONLY,ACTIVE,()->{calls.incrementAndGet();throw http(503);})).isInstanceOf(WebClientResponseException.class);
        assertThat(calls.get()).isEqualTo(4);
        calls.set(0); var e=executor(true);
        for(var dep:Dependency.values()) assertThatThrownBy(()->e.execute(dep,OperationSafety.NON_IDEMPOTENT,ACTIVE,()->{calls.incrementAndGet();throw http(503);})).isInstanceOf(ReliabilityFailure.class);
        assertThat(calls.get()).isEqualTo(4);
        calls.set(0);
        assertThatThrownBy(()->e.execute(Dependency.RAG,OperationSafety.READ_ONLY,ACTIVE,()->{calls.incrementAndGet();throw http(503);})).isInstanceOf(ReliabilityFailure.class);
        assertThat(calls.get()).isOne();
        calls.set(0);
        assertThatThrownBy(()->e.execute(Dependency.LLM_GATEWAY,OperationSafety.READ_ONLY,ACTIVE,()->{calls.incrementAndGet();throw new RuntimeException("429 timeout");})).isInstanceOf(ReliabilityFailure.class);
        assertThat(calls.get()).isOne();
    }
    @Test void circuitIsSharedAcrossRequestsAndHalfOpenProbesRecover() {
        var e=new ReliabilityExecutor(properties(true,2,2),new SimpleMeterRegistry(),()->0.5,d->{});
        assertThatThrownBy(()->e.execute(Dependency.LLM_GATEWAY,OperationSafety.IDEMPOTENT,ACTIVE,()->{throw http(503);})).isInstanceOf(ReliabilityFailure.class);
        assertThat(e.circuitBreaker(Dependency.LLM_GATEWAY).getState()).isEqualTo(CircuitBreaker.State.OPEN);
        var calls=new AtomicInteger();
        assertThatThrownBy(()->e.execute(Dependency.LLM_GATEWAY,OperationSafety.IDEMPOTENT,ACTIVE,()->calls.incrementAndGet()))
                .isInstanceOfSatisfying(ReliabilityFailure.class,f->assertThat(f.category()).isEqualTo(FailureCategory.CIRCUIT_OPEN));
        assertThat(calls.get()).isZero();
        e.circuitBreaker(Dependency.LLM_GATEWAY).transitionToHalfOpenState();
        for(int i=0;i<2;i++) assertThat(e.execute(Dependency.LLM_GATEWAY,OperationSafety.IDEMPOTENT,ACTIVE,()->"ok")).isEqualTo("ok");
        assertThat(e.circuitBreaker(Dependency.LLM_GATEWAY).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
    @Test void bulkheadRejectsThirdCallerWithoutEnteringDependency() throws Exception {
        var e=executor(true); var entered=new CountDownLatch(2); var release=new CountDownLatch(1); var calls=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<String> request=()->e.execute(Dependency.LLM_GATEWAY,OperationSafety.IDEMPOTENT,ACTIVE,()->{
                calls.incrementAndGet();entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError();}catch(InterruptedException x){Thread.currentThread().interrupt();throw new AssertionError(x);}return "ok";
            });
            var first=pool.submit(request);var second=pool.submit(request);
            try {
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->e.execute(Dependency.LLM_GATEWAY,OperationSafety.IDEMPOTENT,ACTIVE,()->{calls.incrementAndGet();return "bad";}))
                        .isInstanceOfSatisfying(ReliabilityFailure.class,f->assertThat(f.category()).isEqualTo(FailureCategory.BULKHEAD_REJECTED));
                assertThat(calls.get()).isEqualTo(2);
            }finally{release.countDown();}
            assertThat(first.get(2,TimeUnit.SECONDS)).isEqualTo("ok");assertThat(second.get(2,TimeUnit.SECONDS)).isEqualTo("ok");
        }
    }
    @Test void retryReleasesPermitAndRechecksCircuitAfterBackoff() {
        var reference=new AtomicReference<ReliabilityExecutor>();var calls=new AtomicInteger();
        var e=new ReliabilityExecutor(properties(true,5,1),new SimpleMeterRegistry(),()->0.5,d->{
            assertThat(reference.get().execute(Dependency.LLM_GATEWAY,OperationSafety.IDEMPOTENT,ACTIVE,()->"probe")).isEqualTo("probe");
            reference.get().circuitBreaker(Dependency.LLM_GATEWAY).transitionToOpenState();
        });reference.set(e);
        assertThatThrownBy(()->e.execute(Dependency.LLM_GATEWAY,OperationSafety.IDEMPOTENT,ACTIVE,()->{calls.incrementAndGet();throw http(503);}))
                .isInstanceOfSatisfying(ReliabilityFailure.class,f->assertThat(f.category()).isEqualTo(FailureCategory.CIRCUIT_OPEN));
        assertThat(calls.get()).isOne();
    }
    @Test void nearDeadlineDoesNotSleepAndCancellationDuringSleepStopsRetry() {
        var sleeps=new AtomicInteger();var calls=new AtomicInteger();
        var e=new ReliabilityExecutor(properties(true,5,1),new SimpleMeterRegistry(),()->0.5,d->sleeps.incrementAndGet());
        var near=new ReliabilityDeadline(){public void checkActive(){}public Duration remaining(){return Duration.ofMillis(21);}};
        assertThatThrownBy(()->e.execute(Dependency.LLM_GATEWAY,OperationSafety.READ_ONLY,near,()->{calls.incrementAndGet();throw http(503);})).isInstanceOf(ReliabilityFailure.class);
        assertThat(calls.get()).isOne();assertThat(sleeps.get()).isZero();
        var cancelled=new AtomicBoolean();var deadline=new ReliabilityDeadline(){public void checkActive(){if(cancelled.get())throw new ReliabilityFailure(FailureCategory.CANCELLED);}public Duration remaining(){return Duration.ofSeconds(10);}};
        var interrupted=new ReliabilityExecutor(properties(true,5,1),new SimpleMeterRegistry(),()->0.5,d->cancelled.set(true));
        calls.set(0);
        assertThatThrownBy(()->interrupted.execute(Dependency.LLM_GATEWAY,OperationSafety.READ_ONLY,deadline,()->{calls.incrementAndGet();throw http(503);}))
                .isInstanceOfSatisfying(ReliabilityFailure.class,f->assertThat(f.category()).isEqualTo(FailureCategory.CANCELLED));
        assertThat(calls.get()).isOne();
    }
    @Test void preflightBlocksRetryBeforeSleepingWithoutCircuitFailure() {
        var calls=new AtomicInteger();var sleeps=new AtomicInteger();
        var e=new ReliabilityExecutor(properties(true,5,1),new SimpleMeterRegistry(),()->0.5,d->sleeps.incrementAndGet());
        assertThatThrownBy(()->e.execute(Dependency.LLM_GATEWAY,OperationSafety.READ_ONLY,ACTIVE,
                ()->{if(calls.get()>0)throw new ReliabilityFailure(FailureCategory.BUDGET_EXCEEDED);},
                ()->{calls.incrementAndGet();throw http(503);})).isInstanceOfSatisfying(ReliabilityFailure.class,f->assertThat(f.category()).isEqualTo(FailureCategory.BUDGET_EXCEEDED));
        assertThat(calls.get()).isOne();assertThat(sleeps.get()).isZero();
        assertThat(e.circuitBreaker(Dependency.LLM_GATEWAY).getMetrics().getNumberOfFailedCalls()).isOne();
    }
    @Test void classifierUsesTypedCauseAndNeverMessages() {
        var c=new FailureClassifier();
        assertThat(c.classify(new RuntimeException("PRIVATE",http(429)))).isEqualTo(FailureCategory.RATE_LIMITED);
        assertThat(c.classify(new RuntimeException("429 503 timeout"))).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(c.classify(new RuntimeException(new IOException("PRIVATE")))).isEqualTo(FailureCategory.TRANSIENT);
        assertThat(c.classify(new java.net.SocketTimeoutException("PRIVATE"))).isEqualTo(FailureCategory.TIMEOUT);
    }
}
