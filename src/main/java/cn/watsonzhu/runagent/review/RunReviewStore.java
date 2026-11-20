package cn.watsonzhu.runagent.review;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "run-agent.run-review.enabled", havingValue = "true")
public final class RunReviewStore {
    private static final int MAX_BYTES = 32 * 1024;

    private final Path directory;
    private final ObjectMapper json;

    public RunReviewStore(@Value("${run-agent.run-review.directory:.run-agent/reviews}") Path directory,
            ObjectMapper json) {
        this.directory = directory;
        this.json = json;
    }

    RunReviewStore publicReports() {
        return new RunReviewStore(directory.resolve("public"), json);
    }

    Optional<RunReviewReport> load(long runId) {
        try (var input = Files.newInputStream(reportPath(runId))) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new Unavailable();
            var report = json.readValue(bytes, RunReviewReport.class);
            if (report == null || report.schemaVersion() != 1 || report.runId() != runId) {
                throw new Unavailable();
            }
            return Optional.of(report);
        } catch (NoSuchFileException exception) {
            return Optional.empty();
        } catch (IOException | RuntimeException exception) {
            throw new Unavailable();
        }
    }

    void save(RunReviewReport report) {
        Path stage = null;
        try {
            byte[] bytes = json.writeValueAsBytes(report);
            if (report.schemaVersion() != 1 || bytes.length > MAX_BYTES) throw new Unavailable();
            Files.createDirectories(directory, permissions("rwx------"));
            stage = Files.createTempFile(directory, ".review-", ".tmp", permissions("rw-------"));
            Files.write(stage, bytes);
            Files.move(stage, reportPath(report.runId()), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException exception) {
            throw new Unavailable();
        } finally {
            if (stage != null) {
                try {
                    Files.deleteIfExists(stage);
                } catch (IOException ignored) {
                    // A failed cleanup must not change the outcome of an atomic commit.
                }
            }
        }
    }

    private FileAttribute<?>[] permissions(String value) {
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(value))};
        }
        return new FileAttribute<?>[0];
    }

    private Path reportPath(long runId) {
        return directory.resolve(runId + ".json");
    }

    static final class Unavailable extends RuntimeException {}
}
