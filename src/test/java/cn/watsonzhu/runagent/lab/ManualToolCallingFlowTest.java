package cn.watsonzhu.runagent.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import cn.watsonzhu.runagent.RunAgentApplication;
import cn.watsonzhu.runagent.config.RunningDataProperties;
import cn.watsonzhu.runagent.model.running.RunningSummary;
import cn.watsonzhu.runagent.prompt.PromptCatalog;
import cn.watsonzhu.runagent.service.RunningDataService;
import cn.watsonzhu.runagent.tool.RunningTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.boot.SpringApplication;
import tools.jackson.databind.ObjectMapper;

/**
 * 仅用于学习低层 Tool Calling 协议，不进入 Controller 或生产调用链。
 * 运行本测试时，可在终端观察“模型请求 → 工具调用 → Java 执行 → 工具结果 → 再次模型请求”。
 */
class ManualToolCallingFlowTest {

    private static final int MAX_MODEL_REQUESTS = 3;
    private static final int MAX_TOOL_CALLS = 3;
    private static final String TOOL_NAME = "getRunningSummary";

    @Test
    void scriptedModelShowsTheCompleteManualRoundTrip() {
        RunningDataService data = mock(RunningDataService.class);
        RunningTools tools = new RunningTools(data);
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 31);
        when(data.summarize(start, end)).thenReturn(new RunningSummary(start, end, 2, 15.0, 3600));
        ScriptedModel model = new ScriptedModel(true);

        FlowResult result = runManually(model, tools, new ObjectMapper(),
                "Today is 2026-09-26.", "8 月份我跑了多少公里？");

        assertThat(result.answer()).isEqualTo("You ran 15 km in August.");
        assertThat(result.modelRequests()).isEqualTo(2);
        assertThat(result.steps()).containsExactly(
                "第 1 次 LLM Request", "Tool Call: getRunningSummary", "Java Execute",
                "Tool Result Message", "第 2 次 LLM Request", "Final Answer");
        assertThat(result.history()).extracting(Message::getMessageType)
                .containsExactly(org.springframework.ai.chat.messages.MessageType.SYSTEM,
                        org.springframework.ai.chat.messages.MessageType.USER,
                        org.springframework.ai.chat.messages.MessageType.ASSISTANT,
                        org.springframework.ai.chat.messages.MessageType.TOOL,
                        org.springframework.ai.chat.messages.MessageType.ASSISTANT);
        verify(data).summarize(start, end);
        printSteps(result);
    }

    @Test
    void modelCanAnswerDirectlyWithoutCallingJava() {
        RunningDataService data = mock(RunningDataService.class);
        FlowResult result = runManually(new ScriptedModel(false), new RunningTools(data), new ObjectMapper(),
                "You are RunAgent.", "什么是 LSD？");

        assertThat(result.modelRequests()).isEqualTo(1);
        assertThat(result.steps()).containsExactly("第 1 次 LLM Request", "Final Answer");
        verifyNoInteractions(data);
    }

    @Test
    void unknownToolNameIsRejectedBeforeJavaExecution() {
        RunningDataService data = mock(RunningDataService.class);
        ChatModel model = new ScriptedModel(true) {
            @Override
            public ChatResponse call(Prompt prompt) {
                return toolCall("unknownTool", "{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-31\"}");
            }
        };

        assertThatThrownBy(() -> runManually(model, new RunningTools(data), new ObjectMapper(),
                "You are RunAgent.", "How far did I run?"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Model requested an unregistered tool");
        verifyNoInteractions(data);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_TOOL_LOOP_LIVE", matches = "1")
    void liveModelShowsTheSameManualRoundTrip() {
        // 显式开启才启动真实 Spring 上下文并调用模型；普通 ./gradlew test 不使用 Key 或网络。
        try (var context = SpringApplication.run(RunAgentApplication.class,
                "--spring.main.web-application-type=none",
                "--logging.level.cn.watsonzhu.runagent.tool=OFF")) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            Path fixture = Path.of("src/test/resources/fixtures/activities.json").toAbsolutePath();
            RunningTools fixtureTools = new RunningTools(new RunningDataService(
                    new RunningDataProperties(fixture.toString()), mapper));
            // 即使环境配置了私人 RUNNING_DATA_PATH，现场实验也只读取合成 fixture。
            String labPrompt = context.getBean(PromptCatalog.class).chatSystemPrompt()
                    + "\nThis is a learning demo using synthetic fixture data. Do not describe it as the user's real history.";
            FlowResult result = runManually(context.getBean(ChatModel.class), fixtureTools, mapper,
                    labPrompt, "2026 年 8 月我跑了多少公里？");
            assertThat(result.steps()).contains("Tool Call: getRunningSummary", "Java Execute", "Tool Result Message",
                    "Final Answer");
            assertThat(result.answer()).isNotBlank();
            printSteps(result);
        }
    }

    private static FlowResult runManually(ChatModel model, RunningTools tools, ObjectMapper mapper,
                                          String systemPrompt, String question) {
        if (!(model.getOptions() instanceof ToolCallingChatOptions modelOptions)) {
            throw new IllegalArgumentException("This model must support ToolCallingChatOptions");
        }
        // 这里只借用 @Tool 生成 Schema；不调用 Spring AI 的 ToolCallingAdvisor 或 ToolCallingManager 执行工具。
        var callbacks = ToolCallbacks.from(tools);
        ToolCallingChatOptions options = modelOptions.mutate().toolCallbacks(callbacks).build();
        List<Message> messages = new ArrayList<>(List.of(new SystemMessage(systemPrompt), new UserMessage(question)));
        List<String> steps = new ArrayList<>();
        int toolCallCount = 0;

        for (int round = 1; round <= MAX_MODEL_REQUESTS; round++) {
            steps.add("第 " + round + " 次 LLM Request");
            ChatResponse response = model.call(new Prompt(List.copyOf(messages), options));
            Generation generation = response.getResult();
            if (generation == null) {
                throw new IllegalStateException("Model returned no generation");
            }
            AssistantMessage assistant = generation.getOutput();
            messages.add(assistant);
            if (!response.hasToolCalls()) {
                steps.add("Final Answer");
                return new FlowResult(assistant.getText(), round, List.copyOf(steps), List.copyOf(messages));
            }

            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                if (++toolCallCount > MAX_TOOL_CALLS) {
                    throw new IllegalStateException("Learning demo tool-call limit exceeded");
                }
                validateToolCall(call);
                steps.add("Tool Call: " + call.name());
                steps.add("Java Execute");
                toolResponses.add(executeToolCall(call, tools, mapper));
            }
            // 必须先保留模型发出的 Assistant Tool Call，再追加匹配 call id 的 Tool Result Message。
            messages.add(ToolResponseMessage.builder().responses(toolResponses).build());
            steps.add("Tool Result Message");
        }
        throw new IllegalStateException("Learning demo model-request limit exceeded");
    }

    private static void validateToolCall(AssistantMessage.ToolCall call) {
        if (!TOOL_NAME.equals(call.name()) || call.id() == null || call.id().isBlank()) {
            throw new IllegalStateException("Model requested an unregistered tool");
        }
    }

    private static ToolResponseMessage.ToolResponse executeToolCall(AssistantMessage.ToolCall call,
                                                                     RunningTools tools, ObjectMapper mapper) {
        try {
            SummaryArguments arguments = mapper.readValue(call.arguments(), SummaryArguments.class);
            RunningSummary summary = tools.getRunningSummary(arguments.startDate(), arguments.endDate());
            return new ToolResponseMessage.ToolResponse(call.id(), call.name(), mapper.writeValueAsString(summary));
        } catch (RuntimeException exception) {
            // 实验仍遵守生产安全边界：文件路径、解析异常和堆栈不能进入下一次模型请求。
            return new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                    "Running data could not be retrieved");
        }
    }

    private static void printSteps(FlowResult result) {
        // 只输出阶段，不打印真实活动、Tool Result 或最终回答。
        result.steps().forEach(System.out::println);
    }

    private record SummaryArguments(String startDate, String endDate) {
    }

    private record FlowResult(String answer, int modelRequests, List<String> steps, List<Message> history) {
    }

    private static class ScriptedModel implements ChatModel {

        private final boolean requestTool;
        private int calls;

        private ScriptedModel(boolean requestTool) {
            this.requestTool = requestTool;
        }

        @Override
        public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks())
                    .singleElement().satisfies(callback ->
                            assertThat(callback.getToolDefinition().name()).isEqualTo(TOOL_NAME));
            if (calls++ == 0 && requestTool) {
                assertThat(prompt.getInstructions()).hasSize(2);
                return toolCall(TOOL_NAME, "{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-31\"}");
            }
            if (requestTool) {
                assertThat(prompt.getInstructions()).hasSize(4);
                ToolResponseMessage result = (ToolResponseMessage) prompt.getInstructions().getLast();
                assertThat(result.getResponses()).singleElement().satisfies(toolResponse ->
                        assertThat(toolResponse.responseData()).contains("totalDistanceKm", "runCount")
                                .doesNotContain("summary_polyline"));
            }
            return textResponse(requestTool ? "You ran 15 km in August." : "LSD is an easy long run.");
        }
    }

    private static ChatResponse toolCall(String name, String arguments) {
        AssistantMessage message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", name, arguments))).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
