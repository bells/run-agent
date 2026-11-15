package cn.watsonzhu.runagent.exception;

public final class MultiAgentException extends RuntimeException {
    public enum Kind { INVALID_REQUEST, INVALID_SUPERVISOR_DECISION, DEPENDENCY_UNAVAILABLE,
        INSUFFICIENT_EVIDENCE, BUDGET_EXCEEDED, TIMEOUT, EXECUTION_FAILED }
    private final Kind kind;

    public MultiAgentException(Kind kind) {
        super(switch (kind) {
            case INVALID_REQUEST -> "Invalid multi-agent question, location or inclusive date range (maximum 366 days)";
            case INVALID_SUPERVISOR_DECISION -> "The supervisor returned an invalid delegation decision";
            case DEPENDENCY_UNAVAILABLE -> "Required specialist evidence is temporarily unavailable";
            case INSUFFICIENT_EVIDENCE -> "No successful specialist evidence is available";
            case BUDGET_EXCEEDED -> "The multi-agent execution budget was exceeded";
            case TIMEOUT -> "The multi-agent request timed out";
            case EXECUTION_FAILED -> "The multi-agent request could not produce a valid answer";
        }, null, false, false);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
