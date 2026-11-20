package cn.watsonzhu.runagent.review;

import java.time.Instant;
import java.util.List;

import cn.watsonzhu.runagent.evidence.RunningEvidenceService.Knowledge;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

public record RunReviewReport(int schemaVersion, long runId,
                              @JsonFormat(shape = JsonFormat.Shape.STRING) Instant generatedAt, Mode mode, Facts facts,
                              List<String> observations, String improvement, String nextRun, int evidenceVersion,
                              String evidenceFingerprint, String model, String promptVersion,
                              @JsonInclude(JsonInclude.Include.NON_NULL) Knowledge knowledge) {
    public RunReviewReport {
        observations = List.copyOf(observations);
    }

    public RunReviewReport(int schemaVersion, long runId, Instant generatedAt, Mode mode, Facts facts,
                           List<String> observations, String improvement, String nextRun, int evidenceVersion,
                           String evidenceFingerprint, String model, String promptVersion) {
        this(schemaVersion, runId, generatedAt, mode, facts, observations, improvement, nextRun, evidenceVersion,
                evidenceFingerprint, model, promptVersion, null);
    }

    public enum Mode {
        SUMMARY, SPLIT_COMPARISON
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Facts(double distanceMeters, long movingSeconds, long averagePaceSecondsPerKm,
                        Long firstHalfPaceSecondsPerKm, Long secondHalfPaceSecondsPerKm,
                        Long paceDeltaSecondsPerKm, Boolean estimated) {}
}
