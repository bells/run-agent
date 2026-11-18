package cn.watsonzhu.runagent.reliability;

import java.time.Duration;

/** Adapts an existing task deadline; dependency retries never create a fresh deadline. */
public interface ReliabilityDeadline {
    void checkActive();
    Duration remaining();
}
