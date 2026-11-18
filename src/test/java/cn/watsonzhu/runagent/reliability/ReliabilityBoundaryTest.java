package cn.watsonzhu.runagent.reliability;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static cn.watsonzhu.runagent.reliability.ReliabilityCoreTest.*;
import static org.assertj.core.api.Assertions.*;

class ReliabilityBoundaryTest {
    @Test void defaultBindingIsDisabledAndConcurrencyIsEightFourFour() {
        var properties=new Binder(new MapConfigurationPropertySource(Map.of("run-agent.reliability.enabled","false")))
                .bind("run-agent.reliability",Bindable.of(ReliabilityProperties.class)).get();
        assertThat(properties.enabled()).isFalse();assertThat(properties.checkpoint().enabled()).isFalse();
        assertThat(properties.bulkhead().llm().maxConcurrentCalls()).isEqualTo(8);
        assertThat(properties.bulkhead().runningMcp().maxConcurrentCalls()).isEqualTo(4);
        assertThat(properties.bulkhead().weatherMcp().maxConcurrentCalls()).isEqualTo(4);
    }
    @ParameterizedTest @ValueSource(strings={"retry.max-attempts=0","retry.max-attempts=4","retry.initial-delay=0ms",
            "retry.multiplier=0.5","retry.max-delay=1ms","retry.jitter=1.1","retry.jitter=-0.1",
            "circuit-breaker.sliding-window-size=1","circuit-breaker.minimum-number-of-calls=0",
            "circuit-breaker.failure-rate-threshold=0","circuit-breaker.failure-rate-threshold=101",
            "circuit-breaker.wait-duration-in-open-state=0s","circuit-breaker.permitted-calls-in-half-open-state=0",
            "bulkhead.llm.max-concurrent-calls=0","bulkhead.running-mcp.max-concurrent-calls=0","bulkhead.weather-mcp.max-concurrent-calls=0"})
    void invalidConfigurationFailsBinding(String entry) {
        var part=entry.split("=",2);
        assertThatThrownBy(()->new Binder(new MapConfigurationPropertySource(Map.of("run-agent.reliability."+part[0],part[1])))
                .bind("run-agent.reliability",Bindable.of(ReliabilityProperties.class))).isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
    }
    @Test void deadlineExpiringInsideFailedOperationDoesNotRecordCircuitFailure() {
        var e=executor(true);var expired=new AtomicBoolean();var calls=new AtomicInteger();
        var deadline=new ReliabilityDeadline(){public void checkActive(){if(expired.get())throw new ReliabilityFailure(FailureCategory.CANCELLED);}public Duration remaining(){return Duration.ofSeconds(10);}};
        assertThatThrownBy(()->e.execute(Dependency.LLM_GATEWAY,OperationSafety.READ_ONLY,deadline,()->{calls.incrementAndGet();expired.set(true);throw http(503);}))
                .isInstanceOfSatisfying(ReliabilityFailure.class,f->assertThat(f.category()).isEqualTo(FailureCategory.CANCELLED));
        assertThat(e.circuitBreaker(Dependency.LLM_GATEWAY).getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(calls.get()).isOne();
    }
    @Test void interruptedBackoffStopsWithoutNextActualAttempt() {
        var calls=new AtomicInteger();var e=new ReliabilityExecutor(properties(true,5,2),new SimpleMeterRegistry(),()->0.5,d->{throw new InterruptedException("PRIVATE");});
        try {
            assertThatThrownBy(()->e.execute(Dependency.LLM_GATEWAY,OperationSafety.READ_ONLY,ACTIVE,()->{calls.incrementAndGet();throw http(503);}))
                    .isInstanceOfSatisfying(ReliabilityFailure.class,f->assertThat(f.category()).isEqualTo(FailureCategory.CANCELLED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(calls.get()).isOne();
        } finally{Thread.interrupted();}
    }
    @Test void healthyAndRetryMetricsReflectActualAttemptsAndSafeTags() {
        var registry=new SimpleMeterRegistry();var calls=new AtomicInteger();
        var e=new ReliabilityExecutor(properties(true,5,2),registry,()->0.5,d->{});
        e.execute(Dependency.LLM_GATEWAY,OperationSafety.READ_ONLY,ACTIVE,()->{if(calls.incrementAndGet()==1)throw http(429);return "ok";});
        assertThat(registry.find("runagent.reliability.attempts").tag("dependency","LLM_GATEWAY").counters().stream().mapToDouble(c->c.count()).sum()).isEqualTo(2);
        assertThat(registry.get("runagent.reliability.retry").tag("failureCategory","RATE_LIMITED").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).anySatisfy(m->assertThat(m.getId().getName()).startsWith("resilience4j.circuitbreaker"));
        assertThat(registry.getMeters()).allSatisfy(m->assertThat(m.getId().toString()).doesNotContain("PRIVATE","503","429"));
    }
    @Test void openAiSdkStatusAndIoExceptionsAreClassifiedWithoutBodyAccess() {
        var classifier=new FailureClassifier();
        var rate=com.openai.errors.RateLimitException.builder().headers(com.openai.core.http.Headers.builder().build())
                .cause(new IllegalStateException("PRIVATE_BODY")).build();
        assertThat(classifier.classify(new RuntimeException("PRIVATE",rate))).isEqualTo(FailureCategory.RATE_LIMITED);
        assertThat(new ReliabilityFailure(classifier.classify(rate)).getCause()).isNull();
    }
}
