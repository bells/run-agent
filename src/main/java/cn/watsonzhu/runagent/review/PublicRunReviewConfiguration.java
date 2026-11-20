package cn.watsonzhu.runagent.review;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.function.BooleanSupplier;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"run-agent.run-review.enabled", "run-agent.public-api.enabled"}, havingValue = "true")
class PublicRunReviewConfiguration {
    @Bean
    RunReviewCoordinator publicRunReviewCoordinator(RunReviewStore store, ObjectProvider<RunReviewGenerator> generator,
            MeterRegistry metrics, @Value("${run-agent.run-review.generation-enabled:false}") boolean generationEnabled,
            @Value("${run-agent.run-review.timeout:25s}") Duration timeout,
            @Value("${run-agent.public-api.review-daily-limit:8}") int dailyLimit) {
        return new RunReviewCoordinator(store.publicReports(), generator, metrics, generationEnabled, timeout,
                new PublicGenerationQuota(dailyLimit, Clock.systemUTC()));
    }

    private static final class PublicGenerationQuota implements BooleanSupplier {
        private static final long MIN_INTERVAL_NANOS = Duration.ofSeconds(4).toNanos();

        private final int dailyLimit;
        private final Clock clock;
        private LocalDate date;
        private int admitted;
        private long lastAdmission = System.nanoTime() - MIN_INTERVAL_NANOS;

        PublicGenerationQuota(int dailyLimit, Clock clock) {
            if (dailyLimit <= 0) throw new IllegalArgumentException("Public review daily limit must be positive");
            this.dailyLimit = dailyLimit;
            this.clock = clock;
        }

        @Override
        public boolean getAsBoolean() {
            LocalDate today = LocalDate.now(clock);
            if (!today.equals(date)) {
                date = today;
                admitted = 0;
            }
            long now = System.nanoTime();
            if (admitted >= dailyLimit || now - lastAdmission < MIN_INTERVAL_NANOS) return false;
            admitted++;
            lastAdmission = now;
            return true;
        }
    }
}
