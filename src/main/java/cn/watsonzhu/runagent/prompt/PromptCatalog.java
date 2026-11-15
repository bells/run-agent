package cn.watsonzhu.runagent.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class PromptCatalog {

    private static final String CURRENT_DATE_TOKEN = "{{CURRENT_DATE}}";

    private final String chatSystemPrompt;
    private final String intentSystemPrompt;
    private final String agentSystemPrompt;
    private final String trainingAnalysisWorkflowSystemPrompt;
    private final String supervisorRoutingPrompt;
    private final String supervisorSynthesisPrompt;
    private final String runningAnalystPrompt;
    private final String knowledgeCoachPrompt;
    private final String weatherAdvisorPrompt;
    private final String compactKnowledgeOutput;
    private final String compactFinalOutput;
    private final String boundedRunningToolPlan;
    private final Clock clock;

    @Autowired
    public PromptCatalog(
            @Value("classpath:prompts/run-agent-system.txt") Resource chatSystemPrompt,
            @Value("classpath:prompts/running-intent-system.txt") Resource intentSystemPrompt,
            @Value("classpath:prompts/run-agent-agent-system.txt") Resource agentSystemPrompt) {
        this(chatSystemPrompt, intentSystemPrompt, agentSystemPrompt, Clock.systemDefaultZone());
    }

    public PromptCatalog(Resource chatSystemPrompt, Resource intentSystemPrompt) {
        this(chatSystemPrompt, intentSystemPrompt, chatSystemPrompt, Clock.systemDefaultZone());
    }

    PromptCatalog(Resource chatSystemPrompt, Resource intentSystemPrompt, Clock clock) {
        this(chatSystemPrompt, intentSystemPrompt, chatSystemPrompt, clock);
    }

    public PromptCatalog(Resource chatSystemPrompt, Resource intentSystemPrompt, Resource agentSystemPrompt, Clock clock) {
        this.chatSystemPrompt = read(chatSystemPrompt);
        this.intentSystemPrompt = read(intentSystemPrompt);
        this.agentSystemPrompt = read(agentSystemPrompt);
        this.trainingAnalysisWorkflowSystemPrompt = read(new ClassPathResource("prompts/training-analysis-workflow-system.txt"));
        this.supervisorRoutingPrompt = read(new ClassPathResource("prompts/multi-agent-supervisor-routing-system.txt"));
        this.supervisorSynthesisPrompt = read(new ClassPathResource("prompts/multi-agent-synthesis-system.txt"));
        this.runningAnalystPrompt = read(new ClassPathResource("prompts/running-analyst-agent-system.txt"));
        this.knowledgeCoachPrompt = read(new ClassPathResource("prompts/knowledge-coach-agent-system.txt"));
        this.weatherAdvisorPrompt = read(new ClassPathResource("prompts/weather-advisor-agent-system.txt"));
        this.compactKnowledgeOutput = read(new ClassPathResource("prompts/knowledge-coach-optimized-output.txt"));
        this.compactFinalOutput = read(new ClassPathResource("prompts/multi-agent-synthesis-optimized-output.txt"));
        this.boundedRunningToolPlan = read(new ClassPathResource("prompts/running-analyst-optimized-tool-plan.txt"));
        this.clock = clock;
    }

    public String chatSystemPrompt() {
        // 每次请求再填入今天的日期，使模型能把“今年”“最近 30 天”转换成具体的 Tool 参数。
        return chatSystemPrompt.replace(CURRENT_DATE_TOKEN, LocalDate.now(clock).toString());
    }

    public String runningIntentSystemPrompt() {
        return intentSystemPrompt.replace(CURRENT_DATE_TOKEN, LocalDate.now(clock).toString());
    }

    public String agentSystemPrompt() {
        return agentSystemPrompt.replace(CURRENT_DATE_TOKEN, LocalDate.now(clock).toString());
    }

    public String trainingAnalysisWorkflowSystemPrompt() {
        return trainingAnalysisWorkflowSystemPrompt;
    }

    public String multiAgentSupervisorRoutingPrompt() { return dated(supervisorRoutingPrompt); }
    public String multiAgentSynthesisPrompt() { return dated(supervisorSynthesisPrompt); }
    public String multiAgentSynthesisPrompt(boolean compact) {
        return multiAgentSynthesisPrompt() + (compact ? "\n" + compactFinalOutput : "");
    }
    public String runningAnalystPrompt() { return dated(runningAnalystPrompt); }
    public String runningAnalystPrompt(boolean bounded) {
        return runningAnalystPrompt() + (bounded ? "\n" + boundedRunningToolPlan : "");
    }
    public String knowledgeCoachPrompt() { return dated(knowledgeCoachPrompt); }
    public String knowledgeCoachPrompt(boolean compact) {
        return knowledgeCoachPrompt() + (compact ? "\n" + compactKnowledgeOutput : "");
    }
    public String weatherAdvisorPrompt() { return dated(weatherAdvisorPrompt); }

    private String dated(String prompt) { return prompt.replace(CURRENT_DATE_TOKEN, LocalDate.now(clock).toString()); }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load prompt resource: " + resource.getDescription(), exception);
        }
    }
}
