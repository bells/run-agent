package cn.watsonzhu.runagent.multiagent;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import cn.watsonzhu.runagent.config.MultiAgentProperties;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.prompt.PromptCatalog;
import cn.watsonzhu.runagent.rag.KnowledgeSearchService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MultiAgentSpecialistsTest {
    static final MultiAgentProperties PROPERTIES = new MultiAgentProperties(Duration.ofSeconds(5), 3, 500, 2000);
    static final LocalDate START = LocalDate.of(2026, 1, 1);
    static final LocalDate END = LocalDate.of(2026, 1, 30);
    static final JsonMapper JSON = new JsonMapper();

    @Test void runningLoopHasOnlyRunningToolsAndEnforcesExplicitDateHints() {
        var seen = new ArrayList<Prompt>();
        var inputs = new ArrayList<String>();
        ToolCallbackProvider provider = () -> new ToolCallback[] {
                tool("getRunningSummary", inputs, "{\"runCount\":2}"), tool("getRecentRuns", inputs, "[]"),
                tool("getPersonalBest", inputs, "{}") };
        ChatModel model = model(prompt -> {
            seen.add(prompt);
            assertTools(prompt, "getRunningSummary", "getRecentRuns", "getPersonalBest");
            if (seen.size() == 1) return toolCall("getRunningSummary", "{\"startDate\":\"2025-01-01\",\"endDate\":\"2025-01-03\"}");
            return answer("r".repeat(700));
        });
        var agent = new RunningAnalystAgent(model, prompts(), provider, manager(), PROPERTIES, ObservationRegistry.NOOP);
        var trace = trace(Specialist.RUNNING_ANALYST, START, END, null);
        assertThat(agent.analyze("original {question}", "running task", START, END, trace)).hasSize(500);
        assertThat(JSON.readTree(inputs.getFirst()).path("startDate").asText()).isEqualTo("2026-01-01");
        assertThat(JSON.readTree(inputs.getFirst()).path("endDate").asText()).isEqualTo("2026-01-30");
        assertThat(seen.getFirst().getInstructions()).hasSize(2);
        assertThat(seen.getFirst().getUserMessage().getText()).contains("original {question}", "running task", "2026-01-01")
                .doesNotContain("knowledgeReport", "weatherReport", "location");
        assertThat(trace.successfulToolCalls()).isEqualTo(1);
    }

    @Test void weatherLoopHasOnlyWeatherToolsAndEnforcesExplicitLocation() {
        var inputs = new ArrayList<String>();
        var count = new AtomicInteger();
        ToolCallbackProvider provider = () -> new ToolCallback[] {
                tool("openmeteo_search_locations", inputs, "{\"latitude\":1,\"longitude\":2}"),
                tool("openmeteo_get_forecast", inputs, "{\"temperature\":18}"), tool("openmeteo_get_air_quality", inputs, "{}") };
        ChatModel model = model(prompt -> {
            assertTools(prompt, "openmeteo_search_locations", "openmeteo_get_forecast", "openmeteo_get_air_quality");
            assertThat(prompt.getUserMessage().getText()).doesNotContain("runningReport", "startDate", "knowledgeReport");
            int step = count.getAndIncrement();
            if (step == 0) return toolCall("openmeteo_search_locations", "{\"name\":\"wrong city\"}");
            if (step == 1) return toolCall("openmeteo_get_forecast", "{\"latitude\":1,\"longitude\":2}");
            return answer("weather report");
        });
        var agent = new WeatherAdvisorAgent(model, prompts(), provider, manager(), PROPERTIES, ObservationRegistry.NOOP);
        agent.analyze("original question", "weather task", "explicit city", trace(Specialist.WEATHER_ADVISOR, null, null, "explicit city"));
        assertThat(JSON.readTree(inputs.getFirst()).path("name").asText()).isEqualTo("explicit city");
    }

    @Test void toolFreeAnswerCannotBecomeEvidenceAndToolErrorsDoNotBecomeReports() {
        var inputs = new ArrayList<String>();
        ToolCallbackProvider provider = () -> new ToolCallback[] { tool("getRunningSummary", inputs, "{}"),
                tool("getRecentRuns", inputs, "[]"), tool("getPersonalBest", inputs, "{}") };
        var agent = new RunningAnalystAgent(model(prompt -> answer("invented statistics")), prompts(), provider, manager(), PROPERTIES, ObservationRegistry.NOOP);
        assertThatThrownBy(() -> agent.analyze("question", "task", null, null, trace(Specialist.RUNNING_ANALYST, null, null, null)))
                .isInstanceOfSatisfying(MultiAgentException.class, e -> assertThat(e.kind()).isEqualTo(MultiAgentException.Kind.INSUFFICIENT_EVIDENCE));
        var count = new AtomicInteger();
        var failing = new RunningAnalystAgent(model(prompt -> count.getAndIncrement() == 0
                ? toolCall("getRunningSummary", "{}") : answer("invented report")), prompts(),
                () -> new ToolCallback[] { tool("getRunningSummary", inputs, "{\"error\":\"secret upstream\"}"),
                        tool("getRecentRuns", inputs, "[]"), tool("getPersonalBest", inputs, "{}") }, manager(), PROPERTIES, ObservationRegistry.NOOP);
        assertThatThrownBy(() -> failing.analyze("question", "task", null, null, trace(Specialist.RUNNING_ANALYST, null, null, null)))
                .isInstanceOfSatisfying(MultiAgentException.class, e -> assertThat(e.kind()).isEqualTo(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE));
    }

    @Test void locationSearchAloneDoesNotEstablishWeatherEvidence() {
        var count = new AtomicInteger();
        ToolCallbackProvider provider = () -> new ToolCallback[] {
                tool("openmeteo_search_locations", new ArrayList<>(), "{\"latitude\":1,\"longitude\":2}"),
                tool("openmeteo_get_forecast", new ArrayList<>(), "{}"), tool("openmeteo_get_air_quality", new ArrayList<>(), "{}") };
        var agent = new WeatherAdvisorAgent(model(prompt -> count.getAndIncrement() == 0
                ? toolCall("openmeteo_search_locations", "{\"name\":\"city\"}") : answer("invented weather")),
                prompts(), provider, manager(), PROPERTIES, ObservationRegistry.NOOP);
        assertThatThrownBy(() -> agent.analyze("question", "task", "city", trace(Specialist.WEATHER_ADVISOR, null, null, "city")))
                .isInstanceOfSatisfying(MultiAgentException.class, e -> assertThat(e.kind()).isEqualTo(MultiAgentException.Kind.INSUFFICIENT_EVIDENCE));
    }

    @Test void forecastCoordinatesMustComeFromSuccessfulLocationSearch() {
        for (boolean searchFirst : List.of(true, false)) {
            var calls = new AtomicInteger();
            var forecasts = new ArrayList<String>();
            ToolCallbackProvider provider = () -> new ToolCallback[] {
                    tool("openmeteo_search_locations", new ArrayList<>(), "{\"results\":[{\"latitude\":1,\"longitude\":2}]}"),
                    tool("openmeteo_get_forecast", forecasts, "{\"temperature\":18}"),
                    tool("openmeteo_get_air_quality", forecasts, "{\"aqi\":10}") };
            ChatModel model = model(prompt -> {
                int call = calls.getAndIncrement();
                if (searchFirst && call == 0) return toolCall("openmeteo_search_locations", "{\"name\":\"wrong city\"}");
                if (call == (searchFirst ? 1 : 0)) return toolCall("openmeteo_get_forecast", "{\"latitude\":50,\"longitude\":60}");
                return answer("wrong city weather report");
            });
            var agent = new WeatherAdvisorAgent(model, prompts(), provider, manager(), PROPERTIES, ObservationRegistry.NOOP);
            assertThatThrownBy(() -> agent.analyze("question", "task", "explicit city", trace(Specialist.WEATHER_ADVISOR, null, null, "explicit city")))
                    .isInstanceOf(MultiAgentException.class);
            assertThat(forecasts).isEmpty();
        }
    }

    @Test void emptyWeatherPayloadsCannotBecomeSuccessfulEvidence() {
        for (String result : List.of("[]", "{}", "null", "[{\"type\":\"text\",\"text\":\"\"}]", "{\"current\":{}}", "{\"latitude\":1,\"longitude\":2}")) {
            var calls = new AtomicInteger();
            ToolCallbackProvider provider = () -> new ToolCallback[] {
                    tool("openmeteo_search_locations", new ArrayList<>(), "{\"latitude\":1,\"longitude\":2}"),
                    tool("openmeteo_get_forecast", new ArrayList<>(), result), tool("openmeteo_get_air_quality", new ArrayList<>(), "{}") };
            var agent = new WeatherAdvisorAgent(model(prompt -> {
                int call = calls.getAndIncrement();
                if (call == 0) return toolCall("openmeteo_search_locations", "{\"name\":\"city\"}");
                if (call == 1) return toolCall("openmeteo_get_forecast", "{\"latitude\":1,\"longitude\":2}");
                return answer("invented weather");
            }), prompts(), provider, manager(), PROPERTIES, ObservationRegistry.NOOP);
            assertThatThrownBy(() -> agent.analyze("question", "task", "city", trace(Specialist.WEATHER_ADVISOR, null, null, "city")))
                    .isInstanceOfSatisfying(MultiAgentException.class, e -> assertThat(e.kind()).isEqualTo(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE));
        }
    }

    @Test void emptyRunningSummaryEnvelopesCannotBecomeEvidence() {
        for (String result : List.of("{}", "[]", "null", "[{\"type\":\"text\",\"text\":\"\"}]")) {
            var calls = new AtomicInteger();
            ToolCallbackProvider provider = () -> new ToolCallback[] {
                    tool("getRunningSummary", new ArrayList<>(), result), tool("getRecentRuns", new ArrayList<>(), "[]"),
                    tool("getPersonalBest", new ArrayList<>(), "{}") };
            var agent = new RunningAnalystAgent(model(prompt -> calls.getAndIncrement() == 0
                    ? toolCall("getRunningSummary", "{}") : answer("invented statistics")), prompts(), provider, manager(), PROPERTIES, ObservationRegistry.NOOP);
            assertThatThrownBy(() -> agent.analyze("question", "task", null, null, trace(Specialist.RUNNING_ANALYST, null, null, null)))
                    .isInstanceOfSatisfying(MultiAgentException.class, e -> assertThat(e.kind()).isEqualTo(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE));
        }
    }

    @Test void legitimateEmptyRecentRunsStillProvidesNoActivitiesEvidence() {
        var calls = new AtomicInteger();
        ToolCallbackProvider provider = () -> new ToolCallback[] {
                tool("getRunningSummary", new ArrayList<>(), "{}"), tool("getRecentRuns", new ArrayList<>(), "[]"),
                tool("getPersonalBest", new ArrayList<>(), "{}") };
        var agent = new RunningAnalystAgent(model(prompt -> calls.getAndIncrement() == 0
                ? toolCall("getRecentRuns", "{}") : answer("No activities in the requested range.")),
                prompts(), provider, manager(), PROPERTIES, ObservationRegistry.NOOP);
        assertThat(agent.analyze("question", "task", null, null, trace(Specialist.RUNNING_ANALYST, null, null, null)))
                .isEqualTo("No activities in the requested range.");
    }

    @Test void capabilityMixingIsRejectedAndEmptyProviderIsUnavailable() {
        ToolCallbackProvider weather = () -> new ToolCallback[] { tool("openmeteo_get_forecast", new ArrayList<>(), "{}") };
        assertThatThrownBy(() -> new RunningAnalystAgent(prompt -> answer("x"), prompts(), weather, manager(), PROPERTIES, ObservationRegistry.NOOP))
                .isInstanceOf(IllegalArgumentException.class);
        var agent = new WeatherAdvisorAgent(prompt -> answer("x"), prompts(), () -> new ToolCallback[0], manager(), PROPERTIES, ObservationRegistry.NOOP);
        assertThat(agent.isAvailable()).isFalse();
    }

    @Test void knowledgeUsesRetrievalAndOwnLlmWithoutToolsOrOtherReports() {
        var search = mock(KnowledgeSearchService.class);
        when(search.isAvailable()).thenReturn(true);
        when(search.retrieveDocuments(anyString(), eq("knowledge task"))).thenReturn(List.of(
                new Document("Synthetic recovery evidence", Map.of("sourceTitle", "Synthetic Book", "absolutePath", "private-path"))));
        ChatModel model = model(prompt -> {
            assertTools(prompt);
            assertThat(prompt.getInstructions()).hasSize(2);
            assertThat(prompt.getUserMessage().getText()).contains("original question", "knowledge task", "Synthetic recovery evidence", "Synthetic Book")
                    .doesNotContain("private-path", "runningReport", "weatherReport");
            return answer("concise knowledge report");
        });
        var agent = new KnowledgeCoachAgent(model, prompts(), search, PROPERTIES, ObservationRegistry.NOOP);
        assertThat(agent.isAvailable()).isTrue();
        assertThat(agent.analyze("original question", "knowledge task", new MultiAgentExecutionControl(PROPERTIES.timeout())))
                .isEqualTo("concise knowledge report");
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void knowledgeModelLifecycleLogsOnlySafeMetadata(boolean success) {
        var search = mock(KnowledgeSearchService.class);
        when(search.isAvailable()).thenReturn(true);
        when(search.retrieveDocuments(anyString(), anyString())).thenReturn(List.of(new Document("private-knowledge-evidence")));
        ChatModel model = model(prompt -> {
            if (!success) throw new IllegalStateException("private-provider-error");
            return answer("private-knowledge-report");
        });
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(KnowledgeCoachAgent.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();logger.addAppender(events);
        try {
            var agent = new KnowledgeCoachAgent(model, prompts(), search, PROPERTIES, ObservationRegistry.NOOP);
            if (success) agent.analyze("private-question", "private-task", new MultiAgentExecutionControl(PROPERTIES.timeout()));
            else assertThatThrownBy(() -> agent.analyze("private-question", "private-task", new MultiAgentExecutionControl(PROPERTIES.timeout())))
                    .isInstanceOf(RuntimeException.class);
        } finally { logger.detachAppender(events);events.stop(); }
        var messages = events.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anyMatch(message -> message.contains("phase=MODEL status=STARTED"))
                .anyMatch(message -> message.contains("phase=MODEL status=" + (success ? "SUCCESS" : "FAILED")));
        assertThat(messages).allSatisfy(message -> assertThat(message).doesNotContain("private-question", "private-task", "private-knowledge-evidence", "private-knowledge-report", "private-provider-error"));
    }

    @Test void emptyRetrievalNeverCallsKnowledgeModelAndUnexpectedToolCallsFail() {
        var search = mock(KnowledgeSearchService.class);
        when(search.isAvailable()).thenReturn(true);
        when(search.retrieveDocuments(anyString(), anyString())).thenReturn(List.of());
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        var agent = new KnowledgeCoachAgent(model, prompts(), search, PROPERTIES, ObservationRegistry.NOOP);
        assertThatThrownBy(() -> agent.analyze("question", "task", new MultiAgentExecutionControl(PROPERTIES.timeout())))
                .isInstanceOf(MultiAgentException.class);
        verify(model, never()).call(any(Prompt.class));
        when(search.retrieveDocuments(anyString(), anyString())).thenReturn(List.of(new Document("evidence")));
        when(model.call(any(Prompt.class))).thenReturn(toolCall("getRunningSummary", "{}"));
        assertThatThrownBy(() -> agent.analyze("question", "task", new MultiAgentExecutionControl(PROPERTIES.timeout())))
                .isInstanceOf(MultiAgentException.class);
    }

    @Test void supervisorUsesStructuredOutputWithoutToolsAndRejectsMalformedOutput() {
        var data = MultiAgentState.initial(new cn.watsonzhu.runagent.model.MultiAgentRequest("original {question}", START, END, "city"),
                "synthetic-id", List.of(Specialist.RUNNING_ANALYST));
        var state = new MultiAgentState(data);
        ChatModel model = model(prompt -> {
            assertTools(prompt);
            assertThat(prompt.getInstructions()).hasSize(2);
            assertThat(prompt.getUserMessage().getText()).contains("original {question}", "RUNNING_ANALYST", "2026-01-01", "city");
            return answer("{\"nextAgent\":\"RUNNING_ANALYST\",\"task\":\"analyze\"}");
        });
        var supervisor = new SupervisorAgent(model, prompts(), ObservationRegistry.NOOP);
        assertThat(supervisor.decideNext(state)).isEqualTo(new SupervisorDecision(Specialist.RUNNING_ANALYST, "analyze"));
        var invalid = new SupervisorAgent(prompt -> answer("{\"nextAgent\":\"UNKNOWN\"}"), prompts(), ObservationRegistry.NOOP);
        assertThatThrownBy(() -> invalid.decideNext(state)).isInstanceOfSatisfying(MultiAgentException.class,
                e -> assertThat(e.kind()).isEqualTo(MultiAgentException.Kind.INVALID_SUPERVISOR_DECISION));
    }

    @Test void toolTraceStaysLocalAndDeadlinePreventsFurtherTools() {
        var control = new MultiAgentExecutionControl(Duration.ofSeconds(5));
        var trace = new MultiAgentExecutionTrace("synthetic-id", Specialist.RUNNING_ANALYST, control, START, END, null);
        var delegate = tool("getRunningSummary", new ArrayList<>(), "{\"runCount\":0}");
        var wrapper = new SpecialistTracingToolCallback(delegate);
        wrapper.call("{}", new ToolContext(Map.of(MultiAgentExecutionTrace.CONTEXT_KEY, trace)));
        assertThat(trace.successfulToolCalls()).isEqualTo(1);
        control.cancel();
        assertThatThrownBy(() -> wrapper.call("{}", new ToolContext(Map.of(MultiAgentExecutionTrace.CONTEXT_KEY, trace))))
                .isInstanceOf(MultiAgentException.class);
    }

    static ChatModel model(java.util.function.Function<Prompt, ChatResponse> script) {
        return new ChatModel() {
            public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
            public ChatResponse call(Prompt prompt) { return script.apply(prompt); }
        };
    }
    static PromptCatalog prompts() {
        return new PromptCatalog(new ClassPathResource("prompts/run-agent-system.txt"),
                new ClassPathResource("prompts/running-intent-system.txt"), new ClassPathResource("prompts/run-agent-agent-system.txt"));
    }
    static ToolCallingManager manager() { return ToolCallingManager.builder().maxCallsPerTool(4).maxTotalToolCalls(8).build(); }
    static MultiAgentExecutionTrace trace(Specialist agent, LocalDate start, LocalDate end, String location) {
        return new MultiAgentExecutionTrace("synthetic-id", agent, new MultiAgentExecutionControl(PROPERTIES.timeout()), start, end, location);
    }
    static ToolCallback tool(String name, List<String> inputs, String result) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name(name).description("Synthetic tool")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}").build(); }
            public String call(String input) { return call(input, new ToolContext(Map.of())); }
            public String call(String input, ToolContext context) {
                assertThat(context.getContext()).isEmpty();
                inputs.add(input);
                return result;
            }
        };
    }
    static void assertTools(Prompt prompt, String... names) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options)
            assertThat(options.getToolCallbacks() == null ? List.<String>of() : options.getToolCallbacks().stream().map(t -> t.getToolDefinition().name()).toList()).containsExactlyInAnyOrder(names);
        else assertThat(names).isEmpty();
    }
    static ChatResponse answer(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    static ChatResponse toolCall(String name, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", name, arguments))).build())));
    }
}
