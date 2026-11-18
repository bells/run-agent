package cn.watsonzhu.runagent.reliability;

public enum OperationSafety {
    READ_ONLY, IDEMPOTENT, NON_IDEMPOTENT;
    public boolean retrySafe() { return this == READ_ONLY || this == IDEMPOTENT; }
}
