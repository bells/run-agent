package cn.watsonzhu.runagent.model;

public record AgentResponse(String conversationId, String executionId, int toolCallCount, String content) {
}
