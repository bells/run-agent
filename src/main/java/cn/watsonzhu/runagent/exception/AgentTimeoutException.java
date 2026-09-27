package cn.watsonzhu.runagent.exception;

public class AgentTimeoutException extends RuntimeException {
    public AgentTimeoutException() {
        super("Agent request deadline exceeded");
    }
}
