package cn.watsonzhu.runagent.evaluation;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import static cn.watsonzhu.runagent.evaluation.EvaluationModels.*;

public final class ScriptedChatModel implements ChatModel {
    private final List<ToolCall> script;
    private final AtomicInteger round = new AtomicInteger();
    public ScriptedChatModel(List<ToolCall> script) { this.script = List.copyOf(script); }
    @Override public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
    @Override public ChatResponse call(Prompt prompt) {
        int index = round.getAndIncrement();
        var message = AssistantMessage.builder().content("Synthetic analysis based on supplied evidence.");
        if (index < script.size()) {
            var call = script.get(index);
            message.content("").toolCalls(List.of(new AssistantMessage.ToolCall("evaluation-" + index, "function", call.name(), call.arguments().toString())));
        }
        return new ChatResponse(List.of(new Generation(message.build())));
    }
}
