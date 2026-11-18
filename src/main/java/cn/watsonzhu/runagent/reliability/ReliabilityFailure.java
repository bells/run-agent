package cn.watsonzhu.runagent.reliability;

/** Safe to retain in resilience events: no provider body, cause, stack, or suppressed payloads. */
public final class ReliabilityFailure extends RuntimeException {
    private final FailureCategory category;
    public ReliabilityFailure(FailureCategory category) {
        super("Dependency operation failed: " + java.util.Objects.requireNonNull(category).name(), null, false, false);
        this.category = category;
    }
    public FailureCategory category() { return category; }
}
