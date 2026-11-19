package cn.watsonzhu.runagent.review;

@FunctionalInterface
public interface RunReviewEvidenceSource {
    RunReviewEvidence load(long runId);
}
