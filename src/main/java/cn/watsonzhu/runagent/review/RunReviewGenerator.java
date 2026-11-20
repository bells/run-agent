package cn.watsonzhu.runagent.review;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

import cn.watsonzhu.runagent.evidence.AdviceContext;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService.Bundle;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService.EvidenceStatus;
import cn.watsonzhu.runagent.prompt.PromptCatalog;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
@ConditionalOnProperty(prefix = "run-agent.run-review", name = {"enabled", "generation-enabled"}, havingValue = "true")
public class RunReviewGenerator {
    private static final int REPORT_SCHEMA_VERSION = 1;
    private static final int EVIDENCE_ALGORITHM_VERSION = 1;
    private static final String PROMPT_VERSION = "run-review-v2";
    private static final int MAX_OUTPUT_CHARS = 4096;
    private static final int MAX_RECOMMENDATION_CHARS = 240;

    private final RunReviewEvidenceSource source;
    private final ChatClient chatClient;
    private final ObjectMapper json;
    private final ObjectReader selectionReader;
    private final SystemMessage systemMessage;
    private final String modelName;
    private final Clock clock;
    private final Function<String, Bundle> knowledgeLookup;

    @Autowired
    public RunReviewGenerator(RunReviewEvidenceSource source, ChatModel model, ObservationRegistry observations,
                              ObjectMapper json, PromptCatalog prompts,
                              @Value("${spring.ai.openai.chat.model}") String modelName,
                              RunningEvidenceService runningEvidence) {
        this(source, model, observations, json, prompts.singleRunReviewSystemPrompt(), modelName, Clock.systemUTC(),
                topic -> runningEvidence.collect(topic, AdviceContext.NONE));
    }

    RunReviewGenerator(RunReviewEvidenceSource source, ChatModel model, ObservationRegistry observations,
                       ObjectMapper json, String systemPrompt, String modelName, Clock clock,
                       Function<String, Bundle> knowledgeLookup) {
        this.source = source;
        this.chatClient = ChatClient.builder(model, observations, null, null).build();
        this.json = json;
        this.selectionReader = json.readerFor(Selection.class)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.systemMessage = new SystemMessage(systemPrompt + "\n" + new BeanOutputConverter<>(Selection.class).getFormat());
        this.modelName = modelName;
        this.clock = clock;
        this.knowledgeLookup = knowledgeLookup;
    }

    public RunReviewReport generate(long runId) {
        RunReviewEvidence evidence = source.load(runId);
        if (evidence == null || !evidence.matches(runId)) {
            throw new RunReviewException(RunReviewException.Code.EVIDENCE_UNAVAILABLE);
        }
        var analysis = RunReviewPaceAnalysis.calculate(evidence);
        var trainingEvidence = knowledgeLookup.apply(trainingTopic(analysis.trend()));
        var knowledge = trainingEvidence.evidence().knowledge();
        String context = knowledge.status() == EvidenceStatus.READY ? trainingEvidence.promptContext() : "";
        var selection = select(analysis, context);
        if (!trainingEvidence.allowsAnswer(selection.nextRun())) throw generationFailed();
        return new RunReviewReport(REPORT_SCHEMA_VERSION, runId, clock.instant(), analysis.mode(), analysis.facts(),
                selection.observationEvidenceIds().stream().map(id -> observation(id, analysis)).toList(),
                improvement(selection.improvementEvidenceId(), analysis.trend()), selection.nextRun().strip(),
                EVIDENCE_ALGORITHM_VERSION, fingerprint(evidence), modelName, PROMPT_VERSION, knowledge);
    }

    private static String trainingTopic(RunReviewPaceAnalysis.PaceTrend trend) {
        return switch (trend) {
            case CLOSE -> "耐力跑均匀配速与下次训练的节奏控制";
            case FASTER -> "耐力跑后程加速、负分段与节奏控制";
            case SLOWER -> "耐力跑后程配速下降、控制起跑节奏与配速分配";
            case UNAVAILABLE -> "耐力跑的平均配速、节奏管理与下次轻松跑";
        };
    }

    private Selection select(RunReviewPaceAnalysis analysis, String context) {
        try {
            ModelFacts facts = ModelFacts.from(analysis.facts());
            Object input = context.isBlank() ? facts : new TrainingContext(facts, context);
            var response = chatClient.prompt()
                    .messages(systemMessage, new UserMessage(json.writeValueAsString(input)))
                    .advisors(advisors -> advisors.param(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), false))
                    .options(OpenAiChatOptions.builder().maxTokens(600))
                    .call().chatResponse();
            if (response == null || response.getResult() == null) throw generationFailed();
            var output = response.getResult().getOutput();
            if (output == null || !output.getToolCalls().isEmpty()) throw generationFailed();
            String text = output.getText();
            if (text == null || text.length() > MAX_OUTPUT_CHARS) throw generationFailed();
            // Use the Spring AI converter for its schema and decode separately without logging model text on failure.
            JsonNode result = selectionReader.readTree(text);
            if (!hasExpectedTypes(result)) throw generationFailed();
            Selection selection = selectionReader.readValue(result);
            if (!valid(selection, analysis.mode())) throw generationFailed();
            return selection;
        } catch (RuntimeException exception) {
            throw generationFailed();
        }
    }

    private static boolean hasExpectedTypes(JsonNode result) {
        JsonNode evidenceIds = result.path("observationEvidenceIds");
        if (!evidenceIds.isArray() || !result.path("improvementEvidenceId").isString()
                || !result.path("nextRun").isString()) return false;
        for (JsonNode evidenceId : evidenceIds) {
            if (!evidenceId.isString()) return false;
        }
        return true;
    }

    private static boolean valid(Selection selection, RunReviewReport.Mode mode) {
        if (selection == null || selection.observationEvidenceIds() == null || selection.nextRun() == null) return false;
        List<EvidenceId> observations = selection.observationEvidenceIds();
        boolean comparison = mode == RunReviewReport.Mode.SPLIT_COMPARISON;
        String suggestion = selection.nextRun().strip();
        return !observations.isEmpty() && observations.size() <= 2
                && observations.stream().noneMatch(Objects::isNull)
                && new HashSet<>(observations).size() == observations.size()
                && observations.contains(EvidenceId.PACE_COMPARISON) == comparison
                && selection.improvementEvidenceId() != null
                && (comparison || selection.improvementEvidenceId() == EvidenceId.OVERALL)
                && !suggestion.isEmpty() && suggestion.codePointCount(0, suggestion.length()) <= MAX_RECOMMENDATION_CHARS
                && suggestion.codePoints().noneMatch(Character::isISOControl);
    }

    private static String observation(EvidenceId id, RunReviewPaceAnalysis analysis) {
        var facts = analysis.facts();
        if (id == EvidenceId.OVERALL) {
            return String.format(Locale.ROOT, "全程 %.2f 公里，移动用时 %d 秒，平均配速 %s。",
                    facts.distanceMeters() / 1000, facts.movingSeconds(), paceLabel(facts.averagePaceSecondsPerKm()));
        }
        String halfPaces = "前半 %s，后半 %s%s。".formatted(paceLabel(facts.firstHalfPaceSecondsPerKm()),
                paceLabel(facts.secondHalfPaceSecondsPerKm()), Boolean.TRUE.equals(facts.estimated()) ? "（估算）" : "");
        return switch (analysis.trend()) {
            case CLOSE -> halfPaces + "前后程节奏接近。";
            case FASTER -> halfPaces + "后半每公里快 %d 秒。".formatted(Math.abs(facts.paceDeltaSecondsPerKm()));
            case SLOWER -> halfPaces + "后半每公里慢 %d 秒。".formatted(facts.paceDeltaSecondsPerKm());
            case UNAVAILABLE -> throw generationFailed();
        };
    }

    private static String improvement(EvidenceId id, RunReviewPaceAnalysis.PaceTrend trend) {
        if (id == EvidenceId.OVERALL) {
            return "下次可以把全程平均配速作为节奏观察的参考。";
        }
        return switch (trend) {
            case CLOSE -> "可以观察下次能否继续维持前后程节奏。";
            case FASTER -> "可以观察下次后半加速时的节奏控制。";
            case SLOWER -> "可以观察放稳起跑节奏后，后半配速差是否缩小。";
            case UNAVAILABLE -> throw generationFailed();
        };
    }

    private static String paceLabel(long secondsPerKm) {
        return String.format(Locale.ROOT, "%d:%02d/km", secondsPerKm / 60, secondsPerKm % 60);
    }

    private String fingerprint(RunReviewEvidence evidence) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(evidence));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw generationFailed();
        }
    }

    private static RunReviewException generationFailed() {
        return new RunReviewException(RunReviewException.Code.REVIEW_GENERATION_FAILED);
    }

    private enum EvidenceId {
        OVERALL, PACE_COMPARISON
    }

    private record Selection(List<EvidenceId> observationEvidenceIds, EvidenceId improvementEvidenceId, String nextRun) {}

    private record TrainingContext(ModelFacts facts, String retrievedTrainingSources) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ModelFacts(double distanceMeters, long movingSeconds, long averagePaceSecondsPerKm,
                              Long firstHalfPaceSecondsPerKm, Long secondHalfPaceSecondsPerKm,
                              Long paceDeltaSecondsPerKm) {
        private static ModelFacts from(RunReviewReport.Facts facts) {
            return new ModelFacts(facts.distanceMeters(), facts.movingSeconds(), facts.averagePaceSecondsPerKm(),
                    facts.firstHalfPaceSecondsPerKm(), facts.secondHalfPaceSecondsPerKm(), facts.paceDeltaSecondsPerKm());
        }
    }
}
