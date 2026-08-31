package cn.watsonzhu.runagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import cn.watsonzhu.runagent.exception.StructuredOutputException;
import cn.watsonzhu.runagent.model.RunningIntent;
import cn.watsonzhu.runagent.model.RunningIntentType;
import cn.watsonzhu.runagent.prompt.PromptCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class RunAgentServiceTest {

    @Test
    void chatUsesAStubChatModelWithoutExternalCalls() {
        RunAgentService service = serviceWith(new StubChatModel("stub answer"));

        assertThat(service.chat("What is an easy run?")).isEqualTo("stub answer");
    }

    @Test
    void streamUsesTheReactiveChatModelStream() {
        RunAgentService service = serviceWith(new StubChatModel("unused", "Hello", " runner"));

        StepVerifier.create(service.stream("hello"))
                .expectNext("Hello", " runner")
                .verifyComplete();
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
        PromptCatalog prompts = new PromptCatalog(
                resource("You are RunAgent."),
                resource("Classify the running intent. Today is {{CURRENT_DATE}}."));
        return new RunAgentService(ChatClient.builder(chatModel), prompts);
    }

    private static ByteArrayResource resource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
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
