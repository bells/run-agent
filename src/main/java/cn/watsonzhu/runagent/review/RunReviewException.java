package cn.watsonzhu.runagent.review;

public final class RunReviewException extends RuntimeException {
    public enum Code {
        RUN_NOT_FOUND, EVIDENCE_UNAVAILABLE, REVIEW_GENERATION_FAILED
    }

    private final Code code;

    public RunReviewException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
