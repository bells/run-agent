package cn.watsonzhu.runagent.performance;

/** Explicit gateway option; PROVIDER_DEFAULT leaves the shared model's options untouched. */
public enum ModelReasoningEffort {
    PROVIDER_DEFAULT, NONE, LOW, HIGH, MAX;

    public String apiValue() {
        return this == PROVIDER_DEFAULT ? null : name().toLowerCase(java.util.Locale.ROOT);
    }
}
