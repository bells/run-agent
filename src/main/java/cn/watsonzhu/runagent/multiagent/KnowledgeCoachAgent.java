package cn.watsonzhu.runagent.multiagent;

import java.util.UUID;
import cn.watsonzhu.runagent.performance.*;
import cn.watsonzhu.runagent.config.MultiAgentProperties;
import cn.watsonzhu.runagent.exception.KnowledgeBaseUnavailableException;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.prompt.PromptCatalog;
import cn.watsonzhu.runagent.rag.KnowledgeSearchService;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeCoachAgent {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KnowledgeCoachAgent.class);
    private final ChatClient client;
    private final PromptCatalog prompts;
    private final KnowledgeSearchService search;
    private final MultiAgentProperties properties;
    public KnowledgeCoachAgent(ChatModel model, PromptCatalog prompts, KnowledgeSearchService search,
                                MultiAgentProperties properties, ObservationRegistry observations) {
        this.client = ChatClient.builder(new BudgetedChatModel(model, ModelStage.KNOWLEDGE_COACH, observations), observations, null, null).build();
        this.prompts = prompts;
        this.search = search;
        this.properties = properties;
    }
    public boolean isAvailable() { return search.isAvailable(); }
    public String analyze(String question, String task, MultiAgentExecutionControl control) {
        control.checkActive();
        if (!isAvailable()) throw new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE);
        try {
            String queryId = UUID.randomUUID().toString();
            var documents = search.retrieveDocuments(queryId, task);
            control.checkActive();
            String evidence = new ContextBudgetPolicy(ExecutionScope.require().properties()).knowledgeEvidence(documents);
            if (evidence.isBlank()) throw new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE);
            String context = "[QUESTION]\n" + question + "\n[KNOWLEDGE TASK]\n" + task
                    + "\n[UNTRUSTED RETRIEVED EVIDENCE]\n" + evidence;
            long started = System.nanoTime();
            String status = "FAILED";
            log.info("ragQueryId={} agent=knowledgeCoach phase=MODEL status=STARTED", queryId);
            try {
                boolean compact = ExecutionScope.require().properties().mode() == PerformanceMode.OPTIMIZED;
                var response = client.prompt().messages(new SystemMessage(prompts.knowledgeCoachPrompt(compact)
                                + "\nReturn at most " + properties.maxReportChars() + " characters."), new UserMessage(context))
                        .advisors(a -> a.param(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), false))
                        .call().chatResponse();
                control.checkActive();
                String report = SpecialistReports.bounded(SpecialistReports.text(response), properties.maxReportChars());
                status = "SUCCESS";
                return report;
            } finally {
                log.info("ragQueryId={} agent=knowledgeCoach phase=MODEL status={} latencyMs={}", queryId, status,
                        java.time.Duration.ofNanos(System.nanoTime() - started).toMillis());
            }
        } catch (KnowledgeBaseUnavailableException exception) {
            throw new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE);
        }
    }
}
