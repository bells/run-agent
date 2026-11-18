package cn.watsonzhu.runagent.multiagent;

import org.springframework.beans.factory.annotation.Autowired;
import cn.watsonzhu.runagent.reliability.Dependency;
import cn.watsonzhu.runagent.reliability.ReliabilityExecutor;
import cn.watsonzhu.runagent.reliability.ReliableChatModel;
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
public class WeatherAdvisorAgent {
    private final ToolSpecialistClient client;
    private final PromptCatalog prompts;
    public WeatherAdvisorAgent(ChatModel model, PromptCatalog prompts,
            @Qualifier("weatherSpecialistTools") ToolCallbackProvider tools, ToolCallingManager manager,
            MultiAgentProperties properties, ObservationRegistry observations) {
        this(model, prompts, tools, manager, properties, observations, null);
    }
    @Autowired
    public WeatherAdvisorAgent(ChatModel model, PromptCatalog prompts,
            @Qualifier("weatherSpecialistTools") ToolCallbackProvider tools, ToolCallingManager manager,
            MultiAgentProperties properties, ObservationRegistry observations,
            ReliabilityExecutor reliability) {
        this.client = new ToolSpecialistClient(reliability == null ? new BudgetedChatModel(model, ModelStage.WEATHER_ADVISOR, observations) : new ReliableChatModel(model, ModelStage.WEATHER_ADVISOR, observations, reliability), tools, Set.of("openmeteo_search_locations", "openmeteo_get_forecast", "openmeteo_get_air_quality"),
                manager, properties, observations, reliability, Dependency.WEATHER_MCP);
        this.prompts = prompts;
    }
    public boolean isAvailable() { return client.isAvailable(); }
    public String analyze(String question, String task, String location, MultiAgentExecutionTrace trace) {
        return client.analyze(prompts.weatherAdvisorPrompt(), "[QUESTION]\n" + question + "\n[WEATHER TASK]\n" + task
                + "\n[AUTHORITATIVE LOCATION HINT]\n" + location, trace);
    }
}
