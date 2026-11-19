package cn.watsonzhu.runagent.review;

import java.util.List;

record RunReviewPaceAnalysis(RunReviewReport.Facts facts, PaceTrend trend) {
    private static final double DISTANCE_TOLERANCE_METERS = 10;
    private static final long TIME_TOLERANCE_SECONDS = 2;
    private static final double BOUNDARY_EPSILON = 1e-9;

    enum PaceTrend {
        UNAVAILABLE, CLOSE, FASTER, SLOWER
    }

    static RunReviewPaceAnalysis calculate(RunReviewEvidence evidence) {
        long averagePace = Math.round(pace(evidence.distanceMeters(), evidence.movingSeconds()));
        SplitTotals totals = validatedTotals(evidence);
        if (totals == null) {
            return new RunReviewPaceAnalysis(new RunReviewReport.Facts(evidence.distanceMeters(), evidence.movingSeconds(),
                    averagePace, null, null, null, null), PaceTrend.UNAVAILABLE);
        }
        double halfwayMeters = totals.distanceMeters() / 2;
        FirstHalf firstHalf = firstHalf(evidence.splits(), halfwayMeters);
        double firstPace = pace(halfwayMeters, firstHalf.seconds());
        double secondPace = pace(halfwayMeters, totals.movingSeconds() - firstHalf.seconds());
        double delta = secondPace - firstPace;
        PaceTrend trend = Math.abs(delta) <= 5 ? PaceTrend.CLOSE : delta < 0 ? PaceTrend.FASTER : PaceTrend.SLOWER;
        return new RunReviewPaceAnalysis(new RunReviewReport.Facts(evidence.distanceMeters(), evidence.movingSeconds(),
                averagePace, Math.round(firstPace), Math.round(secondPace), Math.round(delta), firstHalf.estimated()),
                trend);
    }

    RunReviewReport.Mode mode() {
        return trend == PaceTrend.UNAVAILABLE ? RunReviewReport.Mode.SUMMARY : RunReviewReport.Mode.SPLIT_COMPARISON;
    }

    private static SplitTotals validatedTotals(RunReviewEvidence evidence) {
        List<RunReviewEvidence.Split> splits = evidence.splits();
        double distance = 0;
        long seconds = 0;
        int completeSplits = 0;
        for (int index = 0; index < splits.size(); index++) {
            var split = splits.get(index);
            double meters = split.distanceMeters();
            if (!RunReviewEvidence.positiveDistance(meters) || !RunReviewEvidence.positiveSeconds(split.movingSeconds())) {
                return null;
            }
            if (split.partial()) {
                if (index != splits.size() - 1 || meters >= 1000) return null;
            } else {
                if (meters < 995 || meters > 1005) return null;
                completeSplits++;
            }
            distance += meters;
            seconds += split.movingSeconds();
        }
        if (completeSplits < 2 || Math.abs(distance - evidence.distanceMeters()) > DISTANCE_TOLERANCE_METERS
                || Math.abs(seconds - evidence.movingSeconds()) > TIME_TOLERANCE_SECONDS) {
            return null;
        }
        return new SplitTotals(distance, seconds);
    }

    private static FirstHalf firstHalf(List<RunReviewEvidence.Split> splits, double halfwayMeters) {
        double remaining = halfwayMeters;
        double seconds = 0;
        for (var split : splits) {
            double meters = split.distanceMeters();
            if (remaining < meters - BOUNDARY_EPSILON) {
                return new FirstHalf(seconds + split.movingSeconds() * (remaining / meters), true);
            }
            seconds += split.movingSeconds();
            remaining -= meters;
            if (remaining <= BOUNDARY_EPSILON) break;
        }
        return new FirstHalf(seconds, false);
    }

    private static double pace(double meters, double seconds) {
        double pace = (seconds / meters) * 1000;
        if (!Double.isFinite(pace) || pace > RunReviewEvidence.MAX_SAFE_INTEGER || pace < 0.5) {
            throw new RunReviewException(RunReviewException.Code.EVIDENCE_UNAVAILABLE);
        }
        return pace;
    }

    private record SplitTotals(double distanceMeters, long movingSeconds) {}
    private record FirstHalf(double seconds, boolean estimated) {}
}
