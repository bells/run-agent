package cn.watsonzhu.runagent.multiagent;

import cn.watsonzhu.runagent.exception.MultiAgentException;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;

final class SpecialistReports {
    private SpecialistReports() { }
    static String text(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || !response.getResult().getOutput().getToolCalls().isEmpty()
                || ToolCallLimitExceededException.FINISH_REASON.equals(response.getResult().getMetadata().getFinishReason()))
            throw new MultiAgentException(MultiAgentException.Kind.EXECUTION_FAILED);
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) throw new MultiAgentException(MultiAgentException.Kind.EXECUTION_FAILED);
        return text.strip();
    }
    static String bounded(String text, int maxChars) {
        if (text == null || text.isBlank()) throw new MultiAgentException(MultiAgentException.Kind.EXECUTION_FAILED);
        text = text.strip();
        int end = Math.min(text.length(), maxChars);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
}
