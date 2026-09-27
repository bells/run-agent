package cn.watsonzhu.runagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.watsonzhu.runagent.exception.AiRequestException;
import cn.watsonzhu.runagent.exception.StructuredOutputException;
import cn.watsonzhu.runagent.model.RunningIntent;
import cn.watsonzhu.runagent.model.RunningIntentType;
import cn.watsonzhu.runagent.model.running.RunningSummary;
import cn.watsonzhu.runagent.prompt.PromptCatalog;
import cn.watsonzhu.runagent.tool.RunningTools;
import cn.watsonzhu.runagent.service.RunningDataService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import cn.watsonzhu.runagent.exception.AgentLimitException;
import cn.watsonzhu.runagent.model.running.RunningActivitySummary;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** 用本地 StubChatModel 观察 Spring AI 的请求与回合，不依赖真实模型或 API Key。 */
class RunAgentServiceTest {

    @Test
    void agentExecutesThreeDynamicToolCallsAndCountsThem() {
        RunningDataService data = mock(RunningDataService.class);
        LocalDate recentStart = LocalDate.of(2026, 8, 1);
        LocalDate recentEnd = LocalDate.of(2026, 8, 30);
        LocalDate previousStart = LocalDate.of(2026, 7, 2);
        LocalDate previousEnd = LocalDate.of(2026, 7, 31);
        when(data.summarize(recentStart, recentEnd))
                .thenReturn(new RunningSummary(recentStart, recentEnd, 5, 50, 18000));
        when(data.summarize(previousStart, previousEnd))
                .thenReturn(new RunningSummary(previousStart, previousEnd, 3, 30, 12000));
        when(data.recentRuns(recentStart, recentEnd, 5))
                .thenReturn(List.of(new RunningActivitySummary(1, recentEnd, 10, 3600, 360)));
        AtomicInteger requests = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public ToolCallingChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
            @Override public ChatResponse call(Prompt prompt) {
                int round = requests.getAndIncrement();
                assertThat(prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count())
                        .isEqualTo(round);
                return switch (round) {
                    case 0 -> toolCall("getRunningSummary", "{" +
                            "\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-30\"}");
                    case 1 -> toolCall("getRunningSummary", "{" +
                            "\"startDate\":\"2026-07-02\",\"endDate\":\"2026-07-31\"}");
                    case 2 -> toolCall("getRecentRuns", "{" +
                            "\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-30\",\"limit\":5}");
                    default -> response("Recent period improved based on retrieved data.");
                };
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.error(new UnsupportedOperationException());
            }
        };
        var result = serviceWith(model, data).agent("compare two periods and recent runs");
        assertThat(result.executionId()).isNotBlank();
        assertThat(result.toolCallCount()).isEqualTo(3);
        assertThat(result.content()).contains("improved");
        assertThat(requests.get()).isEqualTo(4);
        verify(data).summarize(recentStart, recentEnd);
        verify(data).summarize(previousStart, previousEnd);
        verify(data).recentRuns(recentStart, recentEnd, 5);
    }

    @Test
    void agentStopsWhenFrameworkToolLimitIsExceeded() {
        RunningDataService data = mock(RunningDataService.class);
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 31);
        when(data.summarize(start, end)).thenReturn(new RunningSummary(start, end, 1, 5, 1500));
        AtomicInteger requests = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public ToolCallingChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
            @Override public ChatResponse call(Prompt prompt) {
                requests.incrementAndGet();
                return toolCall("getRunningSummary",
                        "{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-31\"}");
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.error(new UnsupportedOperationException());
            }
        };
        var manager = ToolCallingManager.builder().maxCallsPerTool(2).maxTotalToolCalls(3).build();
        var builder = ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
                ToolCallingAdvisor.builder().toolCallingManager(manager));
        var prompts = new PromptCatalog(resource("chat"), resource("intent"));
        var service = new RunAgentService(builder, prompts, new RunningTools(data));
        assertThatThrownBy(() -> service.agent("keep checking"))
                .isInstanceOf(AgentLimitException.class);
        assertThat(requests.get()).isEqualTo(3);
        org.mockito.Mockito.verify(data, org.mockito.Mockito.times(2)).summarize(start, end);
    }

    @Test
    void chatUsesAStubChatModelWithoutExternalCalls() {
        RunningDataService data = mock(RunningDataService.class);
        RunAgentService service = serviceWith(new StubChatModel("stub answer"), data);

        assertThat(service.chat("What is an easy run?")).isEqualTo("stub answer");
        verifyNoInteractions(data);
    }

    @Test
    void chatExecutesRegisteredToolThenReturnsModelAnswer() {
        RunningDataService data = mock(RunningDataService.class);
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 31);
        when(data.summarize(start, end)).thenReturn(new RunningSummary(start, end, 2, 15.0, 3600));
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ToolCallingChatOptions getOptions() {
                // 测试模型也要声明支持 ToolCallingChatOptions，ChatClient 才会携带工具定义。
                return ToolCallingChatOptions.builder().build();
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                // 第一回合模拟模型选择 Tool；第二回合检查 Spring AI 已把 Java 执行结果放回对话。
                if (calls.getAndIncrement() == 0) {
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function",
                                    "getRunningSummary", "{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-31\"}")))
                            .build())));
                }
                assertThat(prompt.getInstructions()).anyMatch(ToolResponseMessage.class::isInstance);
                ToolResponseMessage toolResult = prompt.getInstructions().stream()
                        .filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast)
                        .findFirst().orElseThrow();
                assertThat(toolResult.getResponses().getFirst().responseData())
                        .contains("runCount", "totalDistanceKm", "totalDurationSeconds")
                        .doesNotContain("summary_polyline");
                return response("You ran 15 km in August.");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.error(new UnsupportedOperationException());
            }
        };

        assertThat(serviceWith(model, data).chat("How far did I run in August?"))
                .isEqualTo("You ran 15 km in August.");
        assertThat(calls.get()).isEqualTo(2);
        verify(data).summarize(start, end);
    }

    @Test
    void streamingExecutesToolBeforeEmittingFinalAnswer() {
        RunningDataService data = mock(RunningDataService.class);
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 31);
        when(data.summarize(start, end)).thenReturn(new RunningSummary(start, end, 2, 15.0, 3600));
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ToolCallingChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                if (calls.getAndIncrement() == 0) {
                    return Flux.just(toolCallResponse());
                }
                assertThat(prompt.getInstructions()).anyMatch(ToolResponseMessage.class::isInstance);
                return Flux.just(response("You ran 15 km in August."));
            }
        };

        StepVerifier.create(serviceWith(model, data).stream("How far did I run in August?"))
                .expectNext("You ran 15 km in August.")
                .verifyComplete();
        assertThat(calls.get()).isEqualTo(2);
        verify(data).summarize(start, end);
    }

    @Test
    void streamUsesTheReactiveChatModelStream() {
        RunAgentService service = serviceWith(new StubChatModel("unused", "Hello", " runner"));

        StepVerifier.create(service.stream("hello"))
                .expectNext("Hello", " runner")
                .verifyComplete();
    }

    @Test
    void streamingErrorIsSanitizedAndCancellationPropagates() {
        AtomicBoolean cancelled = new AtomicBoolean();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                if (prompt.getContents().contains("fail")) {
                    return Flux.error(new IllegalStateException("private provider detail"));
                }
                return Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true));
            }
        };
        RunAgentService service = serviceWith(model);

        StepVerifier.create(service.stream("fail"))
                .expectError(AiRequestException.class)
                .verify();
        StepVerifier.create(service.stream("wait"))
                .thenCancel()
                .verify();
        assertThat(cancelled.get()).isTrue();
    }

    @Test
    void parseRunningIntentUsesSpringAiEntityMapping() {
        String structuredResponse = """
                {
                  "intent": "RUNNING_ANALYSIS",
                  "startDate": "2026-08-01",
                  "endDate": "2026-08-31",
                  "originalQuestion": "model value"
                }
                """;
        RunAgentService service = serviceWith(new StubChatModel(structuredResponse));

        RunningIntent intent = service.parseRunningIntent("帮我分析最近一个月的跑步情况");

        assertThat(intent.intent()).isEqualTo(RunningIntentType.RUNNING_ANALYSIS);
        assertThat(intent.startDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(intent.endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(intent.originalQuestion()).isEqualTo("帮我分析最近一个月的跑步情况");
    }

    @Test
    void normalizeIntentPreservesDatesAndUsesExactQuestion() {
        RunningIntent modelResult = new RunningIntent(
                RunningIntentType.RUNNING_ANALYSIS,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                "model changed this value");

        RunningIntent normalized = RunAgentService.normalizeIntent(modelResult, "帮我分析最近一个月的跑步情况");

        assertThat(normalized.intent()).isEqualTo(RunningIntentType.RUNNING_ANALYSIS);
        assertThat(normalized.startDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(normalized.endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(normalized.originalQuestion()).isEqualTo("帮我分析最近一个月的跑步情况");
    }

    @Test
    void normalizeIntentRejectsAnInvalidDateRange() {
        RunningIntent modelResult = new RunningIntent(
                RunningIntentType.RUNNING_SUMMARY,
                LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 8, 1),
                null);

        assertThatThrownBy(() -> RunAgentService.normalizeIntent(modelResult, "question"))
                .isInstanceOf(StructuredOutputException.class)
                .hasMessageContaining("startDate");
    }

    @Test
    void runningIntentDefaultsMissingTypeToUnknown() {
        RunningIntent intent = new RunningIntent(null, null, null, "question");

        assertThat(intent.intent()).isEqualTo(RunningIntentType.UNKNOWN);
    }

    private static RunAgentService serviceWith(ChatModel chatModel) {
        return serviceWith(chatModel, mock(RunningDataService.class));
    }

    private static RunAgentService serviceWith(ChatModel chatModel, RunningDataService data) {
        PromptCatalog prompts = new PromptCatalog(
                resource("You are RunAgent."),
                resource("Classify the running intent. Today is {{CURRENT_DATE}}."));
        return new RunAgentService(ChatClient.builder(chatModel), prompts,
                new RunningTools(data));
    }

    private static ByteArrayResource resource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static ChatResponse toolCallResponse() {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "getRunningSummary",
                        "{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-31\"}")))
                .build())));
    }

    private static ChatResponse toolCall(String name, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", name, arguments)))
                .build())));
    }

    private static final class StubChatModel implements ChatModel {

        private final String callContent;
        private final List<String> streamContent;

        private StubChatModel(String callContent, String... streamContent) {
            this.callContent = callContent;
            this.streamContent = List.of(streamContent);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return response(callContent);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.fromIterable(streamContent).map(RunAgentServiceTest::response);
        }
    }
}
