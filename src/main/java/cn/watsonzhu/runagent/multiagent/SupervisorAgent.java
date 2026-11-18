package cn.watsonzhu.runagent.multiagent;

import org.springframework.beans.factory.annotation.Autowired;
import cn.watsonzhu.runagent.reliability.ReliabilityExecutor;
import cn.watsonzhu.runagent.reliability.ReliableChatModel;
import java.util.List;
import cn.watsonzhu.runagent.performance.*;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.prompt.PromptCatalog;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

@Component
public class SupervisorAgent {
    private final ChatClient routeClient;
    private final ChatClient synthesisClient;
    private final PromptCatalog prompts;
    public SupervisorAgent(ChatModel model, PromptCatalog prompts, ObservationRegistry observations) {
        this(model, prompts, observations, null);
    }
    @Autowired
    public SupervisorAgent(ChatModel model, PromptCatalog prompts, ObservationRegistry observations,
            ReliabilityExecutor reliability) {
        this.routeClient = ChatClient.builder(reliability == null ? new BudgetedChatModel(model, ModelStage.SUPERVISOR_ROUTE, observations) : new ReliableChatModel(model, ModelStage.SUPERVISOR_ROUTE, observations, reliability), observations, null, null).build();
        this.synthesisClient = ChatClient.builder(reliability == null ? new BudgetedChatModel(model, ModelStage.SUPERVISOR_SYNTHESIS, observations) : new ReliableChatModel(model, ModelStage.SUPERVISOR_SYNTHESIS, observations, reliability), observations, null, null).build();
        this.prompts = prompts;
    }
    public SupervisorDecision decideNext(MultiAgentState state) {
        // The converter supplies the record/enum schema. Validate the resulting decision again at the graph boundary.
        var converter = new BeanOutputConverter<>(SupervisorDecision.class);
        var response = routeClient.prompt().messages(new SystemMessage(prompts.multiAgentSupervisorRoutingPrompt()
                        + "\n" + converter.getFormat()), new UserMessage(context(state, true)))
                .advisors(a -> a.param(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), false))
                .call().chatResponse();
        try { return converter.convert(SpecialistReports.text(response)); }
        catch (RuntimeException exception) { throw new MultiAgentException(MultiAgentException.Kind.INVALID_SUPERVISOR_DECISION); }
    }
    public String synthesize(MultiAgentState state) {
        boolean compact = ExecutionScope.require().properties().mode() == PerformanceMode.OPTIMIZED;
        var response = synthesisClient.prompt().messages(new SystemMessage(prompts.multiAgentSynthesisPrompt(compact)), new UserMessage(context(state, false)))
                .advisors(a -> a.param(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), false))
                .call().chatResponse();
        return SpecialistReports.text(response);
    }
    private String context(MultiAgentState state, boolean routing) {
        StringBuilder context = new StringBuilder("[QUESTION]\n").append(state.question())
                .append("\n[STRUCTURED HINTS]\nstartDate=").append(state.startDate())
                .append("\nendDate=").append(state.endDate()).append("\nlocation=").append(state.location())
                .append("\n[AVAILABLE SPECIALISTS]\n").append(state.availableAgents())
                .append("\n[COMPLETED SPECIALISTS]\n").append(state.completedAgents())
                .append("\n[SPECIALIST STATUS AND BOUNDED REPORTS]\n");
        for (Specialist agent : List.of(Specialist.RUNNING_ANALYST, Specialist.KNOWLEDGE_COACH, Specialist.WEATHER_ADVISOR)) {
            context.append(agent.name()).append(" = ").append(state.status(agent)).append('\n');
            if (state.status(agent) == SpecialistStatus.SUCCESS) context.append(routing ? new ContextBudgetPolicy(ExecutionScope.require().properties()).routingReport(state.report(agent)) : state.report(agent)).append('\n');
        }
        return context.toString();
    }
}
