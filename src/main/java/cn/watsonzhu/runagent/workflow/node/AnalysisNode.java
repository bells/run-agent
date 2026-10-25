package cn.watsonzhu.runagent.workflow.node;

import java.util.Map;
import cn.watsonzhu.runagent.exception.WorkflowException;
import cn.watsonzhu.runagent.prompt.PromptCatalog;
import cn.watsonzhu.runagent.workflow.TrainingAnalysisContextBuilder;
import cn.watsonzhu.runagent.workflow.TrainingAnalysisState;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class AnalysisNode implements NodeAction<TrainingAnalysisState> {
    private final ChatClient client;
    private final PromptCatalog prompts;
    private final TrainingAnalysisContextBuilder context;

    public AnalysisNode(ChatModel model, PromptCatalog prompts, TrainingAnalysisContextBuilder context) {
        // Building directly from ChatModel avoids shared builder defaults/advisors used by Agent or QA.
        this.client = ChatClient.builder(model).build();
        this.prompts = prompts;
        this.context = context;
    }

    @Override public Map<String, Object> apply(TrainingAnalysisState state) {
        try {
            var response = client.prompt()
                    .messages(new SystemMessage(prompts.trainingAnalysisWorkflowSystemPrompt()), new UserMessage(context.build(state)))
                    .advisors(advisors -> advisors.param(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), false))
                    .call().chatResponse();
            if (response == null || response.getResult() == null) return Map.of("analysis", "");
            var output = response.getResult().getOutput();
            if (!output.getToolCalls().isEmpty()) throw new WorkflowException(WorkflowException.Kind.EXECUTION_FAILED);
            return Map.of("analysis", output.getText() == null ? "" : output.getText());
        } catch (RuntimeException exception) {
            throw new WorkflowException(WorkflowException.Kind.EXECUTION_FAILED);
        }
    }
}
