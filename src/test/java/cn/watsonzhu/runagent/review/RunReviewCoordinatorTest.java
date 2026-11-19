package cn.watsonzhu.runagent.review;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunReviewCoordinatorTest {
    @TempDir Path directory;
    private final RunReviewGenerator generator = mock(RunReviewGenerator.class);

    @Test
    void aDoubleClickKeepsOneJobAndAStoreReopenedAfterShutdownCanReadTheReport() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var expected = report(7);
        when(generator.generate(7)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test did not release the job");
            return expected;
        });
        var service = coordinator(true, Duration.ofSeconds(5));
        try {
            var first = service.generate(7);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.generate(7).get(2, TimeUnit.SECONDS).status()).isEqualTo(202);
            assertThat(service.read(7).state()).isEqualTo("GENERATING");
            assertThat(service.generate(8).get(2, TimeUnit.SECONDS).code()).isEqualTo("REVIEW_BUSY");
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS).report()).isEqualTo(expected);
            assertThat(service.generate(7).get(2, TimeUnit.SECONDS).status()).isEqualTo(200);
            verify(generator, times(1)).generate(7);
        } finally {
            release.countDown();
            service.shutdown();
        }
        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactly("7.json");
        }
        var reopened = coordinator(false, Duration.ofSeconds(5));
        try {
            assertThat(reopened.read(7).report()).isEqualTo(expected);
            assertThat(reopened.generate(7).get(2, TimeUnit.SECONDS).report()).isEqualTo(expected);
            assertThat(reopened.read(8).generationEnabled()).isFalse();
        } finally {
            reopened.shutdown();
        }
    }

    @Test
    void timedOutProvidersCannotWriteALateReportOrOpenAnotherConcurrentSlot() throws Exception {
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        when(generator.generate(7)).thenAnswer(call -> {
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test did not release the job");
                return report(7);
            } finally {
                exited.countDown();
            }
        });
        var service = coordinator(true, Duration.ofMillis(175));
        try {
            var first = service.generate(7);
            assertThat(first.get(2, TimeUnit.SECONDS).code()).isEqualTo("REVIEW_GENERATION_FAILED");
            assertThat(service.read(7).state()).isEqualTo("FAILED");
            assertThat(service.generate(8).get(2, TimeUnit.SECONDS).status()).isEqualTo(429);
            release.countDown();
            assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.read(7).state()).isEqualTo("FAILED");
            assertThat(directory.resolve("7.json")).doesNotExist();
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void aCorruptReportCannotBeReadOrOverwrittenByAnotherGeneration() throws Exception {
        Files.writeString(directory.resolve("8.json"), "{bad");
        var service = coordinator(true, Duration.ofSeconds(3));
        try {
            assertThat(service.read(8).code()).isEqualTo("REVIEW_STORAGE_UNAVAILABLE");
            assertThat(service.generate(8).get(2, TimeUnit.SECONDS).code()).isEqualTo("REVIEW_STORAGE_UNAVAILABLE");
            assertThat(Files.readString(directory.resolve("8.json"))).isEqualTo("{bad");
        } finally {
            service.shutdown();
        }
    }

    private RunReviewCoordinator coordinator(boolean enabled, Duration timeout) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("syntheticGenerator", generator);
        return new RunReviewCoordinator(new RunReviewStore(directory, new JsonMapper()),
                beans.getBeanProvider(RunReviewGenerator.class), new SimpleMeterRegistry(), enabled, timeout);
    }

    private static RunReviewReport report(long id) {
        return new RunReviewReport(1, id, Instant.parse("2026-01-07T00:00:00Z"),
                RunReviewReport.Mode.SUMMARY, new RunReviewReport.Facts(4000, 1200, 300, null, null, null, null),
                List.of("全程平均配速 5:00/km"), "下次观察节奏", "以轻松的节奏起跑",
                1, "0".repeat(64), "synthetic-model", "1");
    }
}
