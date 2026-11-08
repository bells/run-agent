package cn.watsonzhu.runagent.evaluation;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import cn.watsonzhu.runagent.config.MultiAgentProperties;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.model.MultiAgentRequest;
import cn.watsonzhu.runagent.multiagent.*;
import cn.watsonzhu.runagent.observability.ObservationTestSupport;
import cn.watsonzhu.runagent.rag.KnowledgeSearchService;
import cn.watsonzhu.runagent.tool.RunningTools;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import static cn.watsonzhu.runagent.evaluation.EvaluationModels.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Synthetic execution contracts through the real cyclic graph, clients, tools and retrieval. */
final class MultiAgentEvaluation {
    private MultiAgentEvaluation() { }
    static List<CaseResult> run(EvaluationFixtures fixture) {
        var results = new ArrayList<CaseResult>();
        var scripts = fixture.cases("multi-agent-model-scripts.json", MultiAgentScript.class).stream()
                .collect(java.util.stream.Collectors.toMap(MultiAgentScript::id, s -> s));
        for (var item : fixture.cases("multi-agent-cases.json", MultiAgentCase.class)) {
            var checks = new ArrayList<Boolean>();
            try {
                var successful = new java.util.HashSet<String>();
                var coordinator = coordinator(fixture, scripts.get(item.id()), checks, successful);
                var response = coordinator.execute(new MultiAgentRequest(item.question(), LocalDate.parse(item.startDate()),
                        LocalDate.parse(item.endDate()), item.location()));
                boolean delegation = matches(item.expectedAgents(), response.agentsUsed(), item.ordered());
                boolean isolation = !checks.isEmpty() && checks.stream().allMatch(Boolean::booleanValue);
                boolean completion = successful.equals(Set.copyOf(item.expectedSuccessfulAgents()));
                boolean pass = delegation && isolation && completion;
                results.add(new CaseResult(item.id(), Category.MULTI_AGENT, Evidence.SCRIPTED_MULTI_AGENT_CONTRACT,
                        pass ? Status.PASS : Status.FAIL, Map.of("multiAgentDelegationAccuracy", delegation ? 1.0 : 0,
                        "multiAgentCapabilityIsolationAccuracy", isolation ? 1.0 : 0, "multiAgentCompletionAccuracy", completion ? 1.0 : 0), pass ? Reason.OK : Reason.ROUTE_MISMATCH));
            } catch (RuntimeException exception) {
                results.add(new CaseResult(item.id(), Category.MULTI_AGENT, Evidence.SCRIPTED_MULTI_AGENT_CONTRACT, Status.FAIL,
                        Map.of("multiAgentDelegationAccuracy", 0.0, "multiAgentCapabilityIsolationAccuracy", 0.0, "multiAgentCompletionAccuracy", 0.0), Reason.EXECUTION_FAILED));
            }
        }
        return List.copyOf(results);
    }
    static boolean matches(List<String> expected, List<String> actual, boolean ordered) {
        if (actual.size() != expected.size() || actual.stream().distinct().count() != actual.size()) return false;
        return ordered ? expected.equals(actual) : Set.copyOf(expected).equals(Set.copyOf(actual));
    }
    private static MultiAgentCoordinator coordinator(EvaluationFixtures fixture, MultiAgentScript script, List<Boolean> checks, Set<String> successful) {
        if (script == null) throw new IllegalArgumentException("Missing independent supervisor script");
        var properties = new MultiAgentProperties(Duration.ofSeconds(10), 3, 2500, 2000);
        var manager = ToolCallingManager.builder().maxCallsPerTool(4).maxTotalToolCalls(8).build();
        var supervisorRound = new AtomicInteger();
        ChatModel supervisor = model(prompt -> {
            checks.add(tools(prompt).isEmpty());
            if (prompt.getSystemMessage().getText().contains("final RunAgent supervisor")) {
                String context = prompt.getUserMessage().getText();
                for (String name : List.of("RUNNING_ANALYST", "KNOWLEDGE_COACH", "WEATHER_ADVISOR"))
                    if (context.contains(name + " = SUCCESS")) successful.add(name);
                return answer("Synthetic integrated advice with unavailable evidence limitations.");
            }
            int round = supervisorRound.getAndIncrement();
            String next = script.decisions().get(round);
            return answer("{\"nextAgent\":\"" + next + "\",\"task\":\"轻松跑 synthetic subtask\"}");
        });
        ToolCallback[] runningTools = ToolCallbacks.from(new RunningTools(fixture.running));
        if ("RUNNING_ANALYST".equals(script.failedSpecialist())) runningTools = java.util.Arrays.stream(runningTools)
                .map(callback -> tool(callback.getToolDefinition().name(), () -> { throw new IllegalStateException("synthetic private failure"); }))
                .toArray(ToolCallback[]::new);
        var runningSnapshot = runningTools;
        var running = new RunningAnalystAgent(toolModel("running", checks), fixture.prompts, () -> runningSnapshot, manager, properties, ObservationRegistry.NOOP);
        ToolCallback[] weatherTools = Set.of("openmeteo_search_locations", "openmeteo_get_forecast", "openmeteo_get_air_quality").stream()
                .map(name -> tool(name, () -> {
                    if ("WEATHER_ADVISOR".equals(script.failedSpecialist())) throw new IllegalStateException("synthetic private failure");
                    return name.endsWith("search_locations") ? "{\"latitude\":1,\"longitude\":2}" : "{\"temperature\":18,\"synthetic\":true}";
                })).toArray(ToolCallback[]::new);
        var weather = new WeatherAdvisorAgent(toolModel("weather", checks), fixture.prompts, () -> weatherTools, manager, properties, ObservationRegistry.NOOP);
        KnowledgeSearchService search = fixture.search;
        if ("KNOWLEDGE_COACH".equals(script.failedSpecialist())) {
            search = mock(KnowledgeSearchService.class);
            when(search.isAvailable()).thenReturn(true);
            when(search.retrieveDocuments(anyString(), anyString())).thenThrow(new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE));
        }
        var knowledge = new KnowledgeCoachAgent(model(prompt -> {
            checks.add(tools(prompt).isEmpty());
            return answer("Synthetic training principles from retrieved evidence.");
        }), fixture.prompts, search, properties, ObservationRegistry.NOOP);
        return new MultiAgentCoordinator(new SupervisorAgent(supervisor, fixture.prompts, ObservationRegistry.NOOP), running,
                knowledge, weather, properties, ObservationTestSupport.noop());
    }
    private static ChatModel toolModel(String role, List<Boolean> checks) {
        var round = new AtomicInteger();
        return model(prompt -> {
            var names = tools(prompt);
            checks.add(Set.copyOf(names).equals("running".equals(role)
                    ? Set.of("getRunningSummary", "getRecentRuns", "getPersonalBest")
                    : Set.of("openmeteo_search_locations", "openmeteo_get_forecast", "openmeteo_get_air_quality")));
            int step = round.getAndIncrement();
            if ("running".equals(role) && step == 0)
                return toolCall("getRunningSummary", "{\"startDate\":\"2026-01-01\",\"endDate\":\"2026-01-31\"}");
            if ("weather".equals(role) && step == 0) return toolCall("openmeteo_search_locations", "{\"name\":\"Synthetic City\"}");
            if ("weather".equals(role) && step == 1) return toolCall("openmeteo_get_forecast", "{\"latitude\":1,\"longitude\":2}");
            return answer("Synthetic concise " + role + " evidence report.");
        });
    }
    private static List<String> tools(Prompt prompt) {
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options) || options.getToolCallbacks() == null) return List.of();
        return options.getToolCallbacks().stream().map(t -> t.getToolDefinition().name()).toList();
    }
    private static ChatModel model(java.util.function.Function<Prompt, ChatResponse> script) {
        return new ChatModel() {
            public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
            public ChatResponse call(Prompt prompt) { return script.apply(prompt); }
        };
    }
    private static ToolCallback tool(String name, java.util.function.Supplier<String> result) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name(name).description("Synthetic capability")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}").build(); }
            public String call(String input) { return result.get(); }
        };
    }
    private static ChatResponse answer(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    private static ChatResponse toolCall(String name, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("synthetic-call", "function", name, arguments))).build())));
    }
}
