package cn.watsonzhu.runagent.performance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import cn.watsonzhu.runagent.config.MultiAgentProperties;
import cn.watsonzhu.runagent.evaluation.EvaluationFixtures;
import cn.watsonzhu.runagent.model.MultiAgentRequest;
import cn.watsonzhu.runagent.multiagent.*;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import static cn.watsonzhu.runagent.performance.PerformanceCoreTest.*;
import static org.assertj.core.api.Assertions.*;

class PerformanceOutputGuidanceTest {
    @ParameterizedTest @EnumSource(PerformanceMode.class)
    void runningGuidanceIsOptimizedOnlyAndPreservesRealToolLoopAndEvidenceBoundary(PerformanceMode mode) throws Exception {
        try(var fixture=new EvaluationFixtures()) {
            var b=budget(properties(mode,16,30000));
            var seen=new ArrayList<Prompt>();
            ChatModel model=new ChatModel() {
                public org.springframework.ai.model.tool.ToolCallingChatOptions getOptions() {
                    return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
                }
                public ChatResponse call(Prompt prompt) {
                    seen.add(prompt);
                    var message=seen.size()==1 ? AssistantMessage.builder().content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall("summary","function","getRunningSummary",
                                    "{\"startDate\":\"2026-01-01\",\"endDate\":\"2026-01-31\"}"))).build()
                            : new AssistantMessage("synthetic bounded running report");
                    return new ChatResponse(List.of(new Generation(message)));
                }
            };
            var tools=org.springframework.ai.support.ToolCallbacks.from(new cn.watsonzhu.runagent.tool.RunningTools(fixture.running));
            var running=new RunningAnalystAgent(model,fixture.prompts,()->tools,
                    org.springframework.ai.model.tool.ToolCallingManager.builder().maxCallsPerTool(4).maxTotalToolCalls(8).build(),
                    new MultiAgentProperties(Duration.ofSeconds(10),3,2500,2000),ObservationRegistry.NOOP);
            var start=java.time.LocalDate.of(2026,1,1);var end=java.time.LocalDate.of(2026,1,31);
            var trace=new MultiAgentExecutionTrace("safe-id",Specialist.RUNNING_ANALYST,b.control(),start,end,null);
            try(var scope=ExecutionScope.open(b)) {
                assertThat(running.analyze("synthetic advice question","synthetic task",start,end,trace)).isEqualTo("synthetic bounded running report");
            }
            assertThat(trace.successfulToolCalls()).isOne();assertThat(b.modelCalls()).isEqualTo(2);
            assertThat(seen.getFirst().getUserMessage().getText()).doesNotContain("knowledgeReport","weatherReport");
            if(mode==PerformanceMode.OPTIMIZED) assertThat(seen.getFirst().getSystemMessage().getText()).contains("[BOUNDED RUNNING TOOL PLAN]");
            else assertThat(seen.getFirst().getSystemMessage().getText()).isEqualTo(fixture.prompts.runningAnalystPrompt()+"\nReturn at most 2500 characters.");
        }
    }
    @ParameterizedTest @EnumSource(PerformanceMode.class)
    void optimizedGuidanceReachesOnlyItsOwnStageWithoutAddingCallsOrCrossSpecialistEvidence(PerformanceMode mode) throws Exception {
        try(var fixture=new EvaluationFixtures()) {
            var b=budget(properties(mode,16,30000));
            var seen=new ArrayList<Prompt>();
            ChatModel model=prompt->{
                seen.add(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("synthetic concise response"))));
            };
            var knowledge=new KnowledgeCoachAgent(model,fixture.prompts,fixture.search,
                    new MultiAgentProperties(Duration.ofSeconds(10),3,2500,2000),ObservationRegistry.NOOP);
            var supervisor=new SupervisorAgent(model,fixture.prompts,ObservationRegistry.NOOP);
            var data=MultiAgentState.initial(new MultiAgentRequest("synthetic question",null,null,null),"safe-id",List.of(Specialist.RUNNING_ANALYST));
            data.put("runningStatus",SpecialistStatus.SUCCESS);
            data.put("runningReport","PRIVATE_RUNNING_REPORT");
            try(var scope=ExecutionScope.open(b)) {
                knowledge.analyze("synthetic question","轻松跑",b.control());
                supervisor.synthesize(new MultiAgentState(data));
            }
            assertThat(seen).hasSize(2).allSatisfy(prompt->assertThat(prompt.getInstructions()).hasSize(2));
            assertThat(b.modelCalls()).isEqualTo(2);
            assertThat(b.collector().records()).extracting(ModelUsageRecord::stage)
                    .containsExactly(ModelStage.KNOWLEDGE_COACH,ModelStage.SUPERVISOR_SYNTHESIS);
            assertThat(seen.get(0).getUserMessage().getText()).doesNotContain("PRIVATE_RUNNING_REPORT");
            assertThat(seen.get(1).getUserMessage().getText()).contains("PRIVATE_RUNNING_REPORT");
            if(mode==PerformanceMode.OPTIMIZED) {
                assertThat(seen.get(0).getSystemMessage().getText()).contains("[COMPACT KNOWLEDGE OUTPUT]").doesNotContain("[COMPACT FINAL OUTPUT]");
                assertThat(seen.get(1).getSystemMessage().getText()).contains("[COMPACT FINAL OUTPUT]").doesNotContain("[COMPACT KNOWLEDGE OUTPUT]");
            } else {
                assertThat(seen.get(0).getSystemMessage().getText()).isEqualTo(fixture.prompts.knowledgeCoachPrompt()+"\nReturn at most 2500 characters.");
                assertThat(seen.get(1).getSystemMessage().getText()).isEqualTo(fixture.prompts.multiAgentSynthesisPrompt());
            }
        }
    }
}
