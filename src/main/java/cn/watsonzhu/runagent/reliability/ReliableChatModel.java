package cn.watsonzhu.runagent.reliability;

import java.time.Duration;
import cn.watsonzhu.runagent.performance.*;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/** Each actual attempt enters the existing budget collector beneath ToolCallingAdvisor. */
public final class ReliableChatModel implements ChatModel {
    private final BudgetedChatModel budgeted;
    private final ModelStage stage;
    private final ReliabilityExecutor executor;
    public ReliableChatModel(ChatModel raw,ModelStage stage,ObservationRegistry observations,ReliabilityExecutor executor) {
        this.stage=stage;this.executor=executor;
        this.budgeted=new BudgetedChatModel(raw,stage,observations,executor.enabled());
    }
    @Override public ChatOptions getOptions(){return budgeted.getOptions();}
    @Override public ChatResponse call(Prompt prompt) {
        if(!executor.enabled())return budgeted.call(prompt);
        var budget=ExecutionScope.require();
        var deadline=new ReliabilityDeadline() {
            public void checkActive(){budget.checkActive();}
            public Duration remaining(){return budget.control().remaining();}
        };
        return executor.execute(Dependency.LLM_GATEWAY,OperationSafety.IDEMPOTENT,deadline,
                ()->budget.checkCanCall(stage),()->budgeted.call(prompt));
    }
}
