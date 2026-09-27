package cn.watsonzhu.runagent.model;

public record AgentResponse(String executionId, int toolCallCount, String content) {
}
