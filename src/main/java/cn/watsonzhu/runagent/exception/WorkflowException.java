package cn.watsonzhu.runagent.exception;

public final class WorkflowException extends RuntimeException {
    public enum Kind { INVALID_REQUEST, TIMEOUT, DEPENDENCY_UNAVAILABLE, EXECUTION_FAILED }
    private final Kind kind;

    public WorkflowException(Kind kind) {
        super(switch (kind) {
            case INVALID_REQUEST -> "Invalid workflow question, mode or inclusive date range (maximum 366 days)";
            case TIMEOUT -> "The workflow request timed out";
            case DEPENDENCY_UNAVAILABLE -> "Required workflow evidence is temporarily unavailable";
            case EXECUTION_FAILED -> "The workflow could not produce a valid analysis";
        });
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
