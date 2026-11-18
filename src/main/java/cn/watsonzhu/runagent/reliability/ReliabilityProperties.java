package cn.watsonzhu.runagent.reliability;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("run-agent.reliability")
public record ReliabilityProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue Retry retry, @DefaultValue CircuitBreaker circuitBreaker,
        @DefaultValue Bulkhead bulkhead, @DefaultValue Checkpoint checkpoint) {
    public ReliabilityProperties {
        retry = retry == null ? new Retry(2, Duration.ofMillis(200), 2, Duration.ofSeconds(1), .25) : retry;
        circuitBreaker = circuitBreaker == null ? new CircuitBreaker(10,5,50,Duration.ofSeconds(30),2) : circuitBreaker;
        bulkhead = bulkhead == null ? new Bulkhead(null,null,null) : bulkhead;
        checkpoint = checkpoint == null ? new Checkpoint(false) : checkpoint;
    }
    public record Retry(@DefaultValue("2") int maxAttempts, @DefaultValue("200ms") Duration initialDelay,
            @DefaultValue("2") double multiplier, @DefaultValue("1s") Duration maxDelay, @DefaultValue("0.25") double jitter) {
        public Retry {
            if(maxAttempts<1 || maxAttempts>3 || !positive(initialDelay) || !positive(maxDelay)
                    || maxDelay.compareTo(initialDelay)<0 || !Double.isFinite(multiplier) || multiplier<1
                    || !Double.isFinite(jitter) || jitter<0 || jitter>1)
                throw new IllegalArgumentException("Invalid reliability retry configuration");
        }
    }
    public record CircuitBreaker(@DefaultValue("10") int slidingWindowSize, @DefaultValue("5") int minimumNumberOfCalls,
            @DefaultValue("50") float failureRateThreshold, @DefaultValue("30s") Duration waitDurationInOpenState,
            @DefaultValue("2") int permittedCallsInHalfOpenState) {
        public CircuitBreaker {
            if(minimumNumberOfCalls<1 || slidingWindowSize<minimumNumberOfCalls || !Float.isFinite(failureRateThreshold)
                    || failureRateThreshold<1 || failureRateThreshold>100 || !positive(waitDurationInOpenState) || permittedCallsInHalfOpenState<1)
                throw new IllegalArgumentException("Invalid reliability circuit configuration");
        }
    }
    public record Bulkhead(Limits llm, Limits runningMcp, Limits weatherMcp) {
        public Bulkhead {
            llm=llm==null?new Limits(8):llm;
            runningMcp=runningMcp==null?new Limits(4):runningMcp;
            weatherMcp=weatherMcp==null?new Limits(4):weatherMcp;
        }
    }
    public record Limits(@DefaultValue("4") int maxConcurrentCalls) {
        public Limits { if(maxConcurrentCalls<1)throw new IllegalArgumentException("Invalid reliability concurrency configuration"); }
    }
    public record Checkpoint(@DefaultValue("false") boolean enabled) { }
    private static boolean positive(Duration duration) {
        return duration!=null && !duration.isNegative() && !duration.isZero();
    }
}
