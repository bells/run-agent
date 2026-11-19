package cn.watsonzhu.runagent.review;

import java.util.List;

public record RunReviewEvidence(int schemaVersion, long runId, double distanceMeters, long movingSeconds,
                                List<Split> splits) {
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    static final int MAX_SPLITS = 200;

    public RunReviewEvidence {
        splits = List.copyOf(splits);
    }

    boolean matches(long expectedRunId) {
        return schemaVersion == 1 && runId == expectedRunId && validRunId(expectedRunId)
                && positiveDistance(distanceMeters) && positiveSeconds(movingSeconds) && splits.size() <= MAX_SPLITS;
    }

    public static boolean validRunId(long id) {
        return id > 0 && id <= MAX_SAFE_INTEGER;
    }

    static boolean positiveDistance(double meters) {
        return Double.isFinite(meters) && meters > 0;
    }

    static boolean positiveSeconds(long seconds) {
        return seconds > 0 && seconds <= MAX_SAFE_INTEGER;
    }

    public record Split(double distanceMeters, long movingSeconds, boolean partial) {}
}
