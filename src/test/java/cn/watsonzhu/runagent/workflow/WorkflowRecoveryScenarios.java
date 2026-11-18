package cn.watsonzhu.runagent.workflow;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import cn.watsonzhu.runagent.config.WorkflowProperties;
import cn.watsonzhu.runagent.exception.WorkflowException;
import cn.watsonzhu.runagent.model.TrainingAnalysisRequest;
import cn.watsonzhu.runagent.model.running.RunningSummary;
import cn.watsonzhu.runagent.prompt.PromptCatalog;
import cn.watsonzhu.runagent.rag.KnowledgeSearchService;
import cn.watsonzhu.runagent.reliability.recovery.WorkflowRecoveryService;
import cn.watsonzhu.runagent.workflow.node.*;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Shared real-graph scenario used by JUnit and the independent offline verification task. */
public final class WorkflowRecoveryScenarios {
    private WorkflowRecoveryScenarios() {}

    public static int verifyResume() {
        var fixture = new Fixture(true);
        when(fixture.search.retrieveDocuments(anyString(), anyString()))
                .thenThrow(new IllegalStateException("private failure body"))
                .thenReturn(List.of(new Document("raw-secret-chunk", Map.of("sourceTitle", "Synthetic Book"))));
        String executionId = fixture.recovery.create(Fixture.request());
        assertThatThrownBy(() -> fixture.recovery.run(executionId)).isInstanceOf(WorkflowException.class);
        assertThat(fixture.recovery.checkpointStates(executionId)).isNotEmpty();
        var latest = fixture.recovery.checkpointStates(executionId).getFirst();
        assertThat((List<?>) latest.get("executedNodes")).extracting(Object::toString).containsExactly("validateInput", "fetchRunningData");
        assertThat(latest.get("runningSummary")).isEqualTo(Fixture.SUMMARY);
        var response = fixture.recovery.resume(executionId);
        assertThat(response.executionId()).isEqualTo(executionId);
        assertThat(response.executedNodes()).containsExactly("validateInput", "fetchRunningData", "retrieveKnowledge", "analyze", "validateOutput");
        assertThat(response.content()).isEqualTo("synthetic analysis");
        assertThat(fixture.runningCalls).hasValue(1);
        assertThat(fixture.modelCalls).hasValue(1);
        verify(fixture.search, times(2)).retrieveDocuments(anyString(), eq("request-secret-question"));
        assertThat(fixture.recovery.resume(executionId)).isEqualTo(response);
        assertThat(fixture.runningCalls).hasValue(1);
        assertThat(fixture.modelCalls).hasValue(1);
        return fixture.runningCalls.get();
    }

    static final class Fixture {
        static final LocalDate START = LocalDate.of(2026, 1, 1);
        static final LocalDate END = LocalDate.of(2026, 1, 31);
        static final RunningSummary SUMMARY = new RunningSummary(START, END, 3, 20, 7000);
        final KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        final AtomicInteger runningCalls = new AtomicInteger();
        final AtomicInteger modelCalls = new AtomicInteger();
        final AtomicInteger modelFailures = new AtomicInteger();
        final WorkflowRecoveryService recovery;
        final TrainingAnalysisWorkflow workflow;

        Fixture(boolean enabled) { this(enabled, 64, Duration.ofMinutes(30)); }
        Fixture(boolean enabled, int capacity, Duration retention) {
            recovery = new WorkflowRecoveryService(enabled, capacity, retention);
            RunningCapabilityGateway running = (start, end) -> { runningCalls.incrementAndGet(); return SUMMARY; };
            when(search.retrieveDocuments(anyString(), anyString())).thenReturn(List.of(new Document("raw-secret-chunk")));
            ChatModel model = input -> {
                modelCalls.incrementAndGet();
                if (modelFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                    throw new IllegalStateException("private provider body");
                }
                assertThat(input.getUserMessage().getText()).contains("request-secret-question");
                if (input.getUserMessage().getText().contains("[KNOWLEDGE EVIDENCE]")) {
                    assertThat(input.getUserMessage().getText()).contains("raw-secret-chunk");
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("synthetic analysis"))));
            };
            var resource = new ClassPathResource("prompts/run-agent-system.txt");
            workflow = new TrainingAnalysisWorkflow(new ValidateInputNode(), new RunningDataNode(running),
                    new KnowledgeRetrievalNode(search), new AnalysisNode(model, new PromptCatalog(resource, resource),
                    new TrainingAnalysisContextBuilder(), ObservationRegistry.NOOP), new ValidateOutputNode(),
                    new WorkflowProperties(Duration.ofSeconds(5)), cn.watsonzhu.runagent.observability.ObservationTestSupport.noop(), recovery);
        }
        static TrainingAnalysisRequest request() {
            return new TrainingAnalysisRequest("request-secret-question", WorkflowMode.COMBINED, START, END);
        }
    }
}
