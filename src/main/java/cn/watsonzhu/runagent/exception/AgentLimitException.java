package cn.watsonzhu.runagent.exception;

public class AgentLimitException extends RuntimeException {
    public AgentLimitException() {
        super("Agent tool call limit exceeded");
    }
}
