package cn.watsonzhu.runagent.workflow;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import cn.watsonzhu.runagent.config.WorkflowProperties;
import cn.watsonzhu.runagent.controller.TrainingWorkflowController;
import cn.watsonzhu.runagent.exception.WorkflowException;
import cn.watsonzhu.runagent.model.TrainingAnalysisRequest;
import cn.watsonzhu.runagent.model.running.RunningSummary;
import cn.watsonzhu.runagent.prompt.PromptCatalog;
import cn.watsonzhu.runagent.rag.KnowledgeSearchService;
import cn.watsonzhu.runagent.workflow.node.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrainingAnalysisWorkflowTest {
    static final LocalDate START = LocalDate.of(2026, 9, 25);
    static final LocalDate END = LocalDate.of(2026, 10, 24);
    static final RunningSummary SUMMARY = new RunningSummary(START, END, 4, 25, 9000);
    private final RunningCapabilityGateway running = mock(RunningCapabilityGateway.class);
    private final KnowledgeSearchService search = mock(KnowledgeSearchService.class);
    private final AtomicReference<Prompt> prompt = new AtomicReference<>();
    private final ChatModel model = input -> {
        prompt.set(input);
        return new ChatResponse(List.of(new Generation(new AssistantMessage("synthetic analysis"))));
    };

    @BeforeEach void prepare() {
        when(running.getRunningSummary(START, END)).thenReturn(SUMMARY);
        when(search.retrieveDocuments(anyString(), anyString())).thenReturn(List.of(
                new Document("Synthetic evidence: allow recovery.", Map.of("sourceTitle", "Synthetic Book",
                        "absolutePath", "/private/not-for-context", "vector", List.of(1, 2)))));
    }

    @ParameterizedTest @EnumSource(WorkflowMode.class)
    void modeControlsExactNodeOrderAndOnlyRequiredCapabilities(WorkflowMode mode) {
        var response = workflow(model, Duration.ofSeconds(5)).execute(request(mode));
        assertThat(response.mode()).isEqualTo(mode);
        assertThat(response.executionId()).matches("[0-9a-f-]{36}");
        assertThat(response.content()).isEqualTo("synthetic analysis");
        switch (mode) {
            case RUNNING_ONLY -> {
                assertThat(response.executedNodes()).containsExactly("validateInput", "fetchRunningData", "analyze", "validateOutput");
                verifyNoInteractions(search);
            }
            case KNOWLEDGE_ONLY -> {
                assertThat(response.executedNodes()).containsExactly("validateInput", "retrieveKnowledge", "analyze", "validateOutput");
                verifyNoInteractions(running);
            }
            case COMBINED -> assertThat(response.executedNodes()).containsExactly("validateInput", "fetchRunningData", "retrieveKnowledge", "analyze", "validateOutput");
        }
        String context = prompt.get().getUserMessage().getText();
        if (mode != WorkflowMode.KNOWLEDGE_ONLY) {
            verify(running).getRunningSummary(START, END);
            assertThat(context).contains("[RUNNING DATA]", "Run count: 4", "Distance (km): 25.0");
        } else assertThat(context).doesNotContain("[RUNNING DATA]", "25.0");
        if (mode != WorkflowMode.RUNNING_ONLY) {
            verify(search).retrieveDocuments(argThat(id -> !id.equals(response.executionId())), eq("analyze {training}"));
            assertThat(context).contains("[KNOWLEDGE EVIDENCE]", "Synthetic Book", "allow recovery");
        } else assertThat(context).doesNotContain("[KNOWLEDGE EVIDENCE]", "Synthetic Book");
        assertThat(context).doesNotContain("absolutePath", "/private/", "vector");
        assertThat(prompt.get().getInstructions()).hasSize(2);
        if (prompt.get().getOptions() instanceof ToolCallingChatOptions options) {
            assertThat(options.getToolCallbacks()).isNullOrEmpty();
            assertThat(options.getToolContext()).isNullOrEmpty();
        }
    }

    @Test void invalidDatesFailBeforeAnyCapabilityOrModel() {
        var workflow = workflow(model, Duration.ofSeconds(5));
        for (var input : List.of(
                new TrainingAnalysisRequest("q", WorkflowMode.COMBINED, null, END),
                new TrainingAnalysisRequest("q", WorkflowMode.RUNNING_ONLY, END, START),
                new TrainingAnalysisRequest("q", WorkflowMode.RUNNING_ONLY, START, START.plusDays(366)),
                new TrainingAnalysisRequest("q", WorkflowMode.KNOWLEDGE_ONLY, START, null),
                new TrainingAnalysisRequest(" ", WorkflowMode.KNOWLEDGE_ONLY, null, null),
                new TrainingAnalysisRequest("x".repeat(4001), WorkflowMode.KNOWLEDGE_ONLY, null, null),
                new TrainingAnalysisRequest("q", null, null, null))) {
            assertThatThrownBy(() -> workflow.execute(input)).isInstanceOf(WorkflowException.class)
                    .extracting("kind").isEqualTo(WorkflowException.Kind.INVALID_REQUEST);
        }
        verifyNoInteractions(running, search);
        assertThat(prompt.get()).isNull();
    }

    @Test void inclusive366DaysIsAccepted() {
        var end = START.plusDays(365);
        when(running.getRunningSummary(START, end)).thenReturn(new RunningSummary(START, end, 0, 0, 0));
        assertThat(workflow(model, Duration.ofSeconds(5)).execute(
                new TrainingAnalysisRequest("q", WorkflowMode.RUNNING_ONLY, START, end)).content()).isNotBlank();
    }

    @Test void runningFailureStopsRagAndAnalysis() {
        when(running.getRunningSummary(any(), any())).thenThrow(new IllegalStateException("private payload"));
        assertDependencyFailure(() -> workflow(model, Duration.ofSeconds(5)).execute(request(WorkflowMode.COMBINED)));
        verifyNoInteractions(search);
        assertThat(prompt.get()).isNull();
    }

    @Test void emptyOrFailedRetrievalNeverFallsBackToModelKnowledge() {
        var workflow = workflow(model, Duration.ofSeconds(5));
        when(search.retrieveDocuments(anyString(), anyString())).thenReturn(List.of());
        assertDependencyFailure(() -> workflow.execute(request(WorkflowMode.COMBINED)));
        when(search.retrieveDocuments(anyString(), anyString())).thenThrow(new IllegalStateException("private book"));
        assertDependencyFailure(() -> workflow.execute(request(WorkflowMode.KNOWLEDGE_ONLY)));
        assertThat(prompt.get()).isNull();
    }

    @Test void providerFailureAndEmptyAnalysisAreControlledFailures() {
        ChatModel failed = input -> { throw new IllegalStateException("private provider response"); };
        assertThatThrownBy(() -> workflow(failed, Duration.ofSeconds(5)).execute(request(WorkflowMode.RUNNING_ONLY)))
                .isInstanceOf(WorkflowException.class).hasMessageNotContaining("private");
        ChatModel empty = input -> new ChatResponse(List.of(new Generation(new AssistantMessage(" "))));
        assertThatThrownBy(() -> workflow(empty, Duration.ofSeconds(5)).execute(request(WorkflowMode.RUNNING_ONLY)))
                .isInstanceOf(WorkflowException.class).extracting("kind").isEqualTo(WorkflowException.Kind.EXECUTION_FAILED);
    }

    @Test void expiredDependencyDoesNotAdvanceToRetrievalOrAnalysis() {
        when(running.getRunningSummary(any(), any())).thenAnswer(call -> {
            Thread.sleep(80);
            return SUMMARY;
        });
        assertThatThrownBy(() -> workflow(model, Duration.ofMillis(30)).execute(request(WorkflowMode.COMBINED)))
                .isInstanceOf(WorkflowException.class).extracting("kind").isEqualTo(WorkflowException.Kind.TIMEOUT);
        verifyNoInteractions(search);
        assertThat(prompt.get()).isNull();
    }

    @Test void compiledGraphDoesNotShareStateAcrossConcurrentRequests() {
        ChatModel echo = input -> new ChatResponse(List.of(new Generation(new AssistantMessage(input.getUserMessage().getText()))));
        var workflow = workflow(echo, Duration.ofSeconds(5));
        var first = CompletableFuture.supplyAsync(() -> workflow.execute(
                new TrainingAnalysisRequest("first question", WorkflowMode.RUNNING_ONLY, START, END)));
        var second = CompletableFuture.supplyAsync(() -> workflow.execute(
                new TrainingAnalysisRequest("second question", WorkflowMode.KNOWLEDGE_ONLY, null, null)));
        assertThat(first.join().content()).contains("first question").doesNotContain("second question", "Synthetic Book");
        assertThat(second.join().content()).contains("second question").doesNotContain("first question", "[RUNNING DATA]");
        assertThat(first.join().executionId()).isNotEqualTo(second.join().executionId());
        assertThat(first.join().executedNodes()).doesNotContain("retrieveKnowledge");
        assertThat(second.join().executedNodes()).doesNotContain("fetchRunningData");
    }

    @Test void clientCancellationStopsGraphEvenWhenDependencySwallowsInterrupt() throws Exception {
        var entered = new CountDownLatch(1);
        var analysisCalled = new CountDownLatch(1);
        when(running.getRunningSummary(any(), any())).thenAnswer(call -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // Simulates a provider that consumes the cancellation interrupt and still returns a result.
            }
            return SUMMARY;
        });
        ChatModel observed = input -> {
            analysisCalled.countDown();
            return model.call(input);
        };
        var controller = new TrainingWorkflowController(workflow(observed, Duration.ofSeconds(5)),
                new WorkflowProperties(Duration.ofSeconds(5)));
        var subscription = controller.analyze(request(WorkflowMode.COMBINED)).subscribe();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            subscription.dispose();
            assertThat(analysisCalled.await(300, TimeUnit.MILLISECONDS)).isFalse();
            verifyNoInteractions(search);
        } finally {
            subscription.dispose();
        }
    }

    @Test void outputValidatorRejectsMissingRequiredEvidence() {
        var node = new ValidateOutputNode();
        assertThatThrownBy(() -> node.apply(new TrainingAnalysisState(Map.of("mode", WorkflowMode.RUNNING_ONLY, "analysis", "answer"))))
                .isInstanceOf(WorkflowException.class);
        assertThatThrownBy(() -> node.apply(new TrainingAnalysisState(Map.of("mode", WorkflowMode.KNOWLEDGE_ONLY, "analysis", "answer"))))
                .isInstanceOf(WorkflowException.class);
    }

    private TrainingAnalysisWorkflow workflow(ChatModel chat, Duration timeout) {
        var resource = new ClassPathResource("prompts/run-agent-system.txt");
        var prompts = new PromptCatalog(resource, resource);
        return new TrainingAnalysisWorkflow(new ValidateInputNode(), new RunningDataNode(running),
                new KnowledgeRetrievalNode(search), new AnalysisNode(chat, prompts, new TrainingAnalysisContextBuilder()),
                new ValidateOutputNode(), new WorkflowProperties(timeout));
    }

    private TrainingAnalysisRequest request(WorkflowMode mode) {
        return new TrainingAnalysisRequest("analyze {training}", mode,
                mode == WorkflowMode.KNOWLEDGE_ONLY ? null : START, mode == WorkflowMode.KNOWLEDGE_ONLY ? null : END);
    }

    private void assertDependencyFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(WorkflowException.class).hasMessageNotContaining("private")
                .extracting("kind").isEqualTo(WorkflowException.Kind.DEPENDENCY_UNAVAILABLE);
    }
}
