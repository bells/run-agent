package cn.watsonzhu.runagent.multiagent;

import java.time.LocalDate;
import java.util.Set;
import cn.watsonzhu.runagent.performance.*;
import cn.watsonzhu.runagent.config.MultiAgentProperties;
import cn.watsonzhu.runagent.prompt.PromptCatalog;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class RunningAnalystAgent {
    private final ToolSpecialistClient client;
    private final PromptCatalog prompts;
    public RunningAnalystAgent(ChatModel model, PromptCatalog prompts,
            @Qualifier("runningSpecialistTools") ToolCallbackProvider tools, ToolCallingManager manager,
            MultiAgentProperties properties, ObservationRegistry observations) {
        this.client = new ToolSpecialistClient(new BudgetedChatModel(model, ModelStage.RUNNING_ANALYST, observations), tools, Set.of("getRunningSummary", "getRecentRuns", "getPersonalBest"),
                manager, properties, observations);
        this.prompts = prompts;
    }
    public boolean isAvailable() { return client.isAvailable(); }
    public String analyze(String question, String task, LocalDate startDate, LocalDate endDate, MultiAgentExecutionTrace trace) {
        boolean bounded = ExecutionScope.require().properties().mode() == PerformanceMode.OPTIMIZED;
        return client.analyze(prompts.runningAnalystPrompt(bounded), "[QUESTION]\n" + question + "\n[RUNNING TASK]\n" + task
                + "\n[AUTHORITATIVE DATE HINTS]\nstartDate=" + startDate + "\nendDate=" + endDate, trace);
    }
}
