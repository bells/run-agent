package cn.watsonzhu.runagent.review;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import cn.watsonzhu.runagent.prompt.PromptCatalog;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunReviewGeneratorTest {
    private static final long RUN_ID = 864213578L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-19T10:15:30Z"), ZoneOffset.UTC);
    private final JsonMapper json = new JsonMapper();
    private final PromptCatalog prompts = new PromptCatalog(new ClassPathResource("prompts/run-agent-system.txt"),
            new ClassPathResource("prompts/running-intent-system.txt"));
    private final AtomicReference<Prompt> sent = new AtomicReference<>();

    @Test
    void fourKilometersAreGroundedInActualPacesAndTheModelOnlySeesAllowedNumbers() {
        var evidence = evidence(4000, 1260, List.of(split(1000, 300, false), split(1000, 300, false),
                split(1000, 330, false), split(1000, 330, false)));
        var report = generator(evidence, """
                {"observationEvidenceIds":["OVERALL","PACE_COMPARISON"],"improvementEvidenceId":"PACE_COMPARISON",
                "nextRun":"下次保持起跑节奏平稳，留意后半配速。"}
                """).generate(RUN_ID);

        assertThat(report.mode()).isEqualTo(RunReviewReport.Mode.SPLIT_COMPARISON);
        assertThat(report.runId()).isEqualTo(RUN_ID);
        assertThat(report.generatedAt()).isEqualTo(CLOCK.instant());
        assertThat(json.readTree(json.writeValueAsString(report)).get("generatedAt").asString())
                .isEqualTo("2026-11-19T10:15:30Z");
        assertThat(report.facts().averagePaceSecondsPerKm()).isEqualTo(315L);
        assertThat(report.facts().firstHalfPaceSecondsPerKm()).isEqualTo(300L);
        assertThat(report.facts().secondHalfPaceSecondsPerKm()).isEqualTo(330L);
        assertThat(report.facts().paceDeltaSecondsPerKm()).isEqualTo(30L);
        assertThat(report.facts().estimated()).isFalse();
        assertThat(report.observations()).anySatisfy(observation ->
                assertThat(observation).contains("5:00/km", "5:30/km", "慢", "30 秒"));
        assertThat(report.evidenceFingerprint()).matches("[a-f0-9]{64}");
        assertThat(report.nextRun()).isEqualTo("下次保持起跑节奏平稳，留意后半配速。");
        String user = sent.get().getInstructions().stream()
                .filter(message -> message.getMessageType() == MessageType.USER)
                .findFirst().orElseThrow().getText();
        assertThat(json.readTree(user).propertyNames()).containsExactlyInAnyOrder(
                "distanceMeters", "movingSeconds", "averagePaceSecondsPerKm", "firstHalfPaceSecondsPerKm",
                "secondHalfPaceSecondsPerKm", "paceDeltaSecondsPerKm");
        assertThat(user).doesNotContain(Long.toString(RUN_ID), CLOCK.instant().toString(), "test-model");
    }

    @Test
    void midpointInsideASplitUsesDistanceProportionalTimeIncludingShortTail() {
        var report = generator(evidence(2500, 930,
                List.of(split(1000, 300, false), split(1000, 360, false), split(500, 270, true))), comparison())
                .generate(RUN_ID);

        assertThat(report.facts().estimated()).isTrue();
        assertThat(report.facts().firstHalfPaceSecondsPerKm()).isEqualTo(312L);
        assertThat(report.facts().secondHalfPaceSecondsPerKm()).isEqualTo(432L);
        assertThat(report.facts().paceDeltaSecondsPerKm()).isEqualTo(120L);
    }

    @Test
    void closePaceUsesTheUnroundedFiveSecondThreshold() {
        var report = generator(evidence(4000, 1210, List.of(split(1000, 302, false), split(1000, 298, false),
                split(1000, 301, false), split(1000, 309, false))), comparison()).generate(RUN_ID);
        assertThat(report.facts().paceDeltaSecondsPerKm()).isEqualTo(5L);
        assertThat(report.observations()).first().asString().contains("接近");
    }

    @Test
    void invalidCoverageFallsBackToOverallFactsWithNoSplitFieldsOnTheWire() {
        var report = generator(evidence(4000, 1260, List.of(split(1000, 300, false), split(1000, 300, false))),
                summary()).generate(RUN_ID);

        assertThat(report.mode()).isEqualTo(RunReviewReport.Mode.SUMMARY);
        assertThat(json.readTree(json.writeValueAsString(report)).get("facts").propertyNames())
                .containsExactlyInAnyOrder("distanceMeters", "movingSeconds", "averagePaceSecondsPerKm");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"observationEvidenceIds\":[\"PACE_COMPARISON\"],\"improvementEvidenceId\":\"OVERALL\",\"nextRun\":\"留意配速\"}",
            "{\"observationEvidenceIds\":[\"OVERALL\",\"OVERALL\"],\"improvementEvidenceId\":\"OVERALL\",\"nextRun\":\"留意配速\"}",
            "{\"observationEvidenceIds\":[\"OVERALL\"],\"improvementEvidenceId\":\"UNKNOWN\",\"nextRun\":\"留意配速\"}",
            "{\"observationEvidenceIds\":[\"OVERALL\"],\"improvementEvidenceId\":\"OVERALL\",\"nextRun\":\" \"}",
            "{\"observationEvidenceIds\":[\"OVERALL\"],\"improvementEvidenceId\":\"OVERALL\",\"nextRun\":\"留意配速\",\"memo\":\"extra\"}",
            "{\"observationEvidenceIds\":[0],\"improvementEvidenceId\":\"OVERALL\",\"nextRun\":\"留意配速\"}",
            "{\"observationEvidenceIds\":[\"OVERALL\"],\"improvementEvidenceId\":\"OVERALL\",\"nextRun\":42}"
    })
    void modelCannotCiteUnavailableFactsOrReturnInvalidStructures(String modelOutput) {
        var generator = generator(evidence(4000, 1260, List.of()), modelOutput);
        assertThatThrownBy(() -> generator.generate(RUN_ID)).isInstanceOfSatisfying(RunReviewException.class,
                exception -> assertThat(exception.code()).isEqualTo(RunReviewException.Code.REVIEW_GENERATION_FAILED));
    }

    @Test
    void evidenceIdentityIsCheckedBeforeCallingTheModel() {
        var generator = generator(new RunReviewEvidence(1, RUN_ID + 1, 4000, 1260, List.of()), summary());
        assertThatThrownBy(() -> generator.generate(RUN_ID)).isInstanceOfSatisfying(RunReviewException.class,
                exception -> assertThat(exception.code()).isEqualTo(RunReviewException.Code.EVIDENCE_UNAVAILABLE));
        assertThat(sent.get()).isNull();
    }

    private RunReviewGenerator generator(RunReviewEvidence evidence, String modelOutput) {
        ChatModel model = prompt -> {
            sent.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(modelOutput))));
        };
        return new RunReviewGenerator(id -> evidence, model, ObservationRegistry.NOOP, json,
                prompts.singleRunReviewSystemPrompt(), "test-model", CLOCK);
    }

    private static String comparison() {
        return """
                {"observationEvidenceIds":["PACE_COMPARISON"],"improvementEvidenceId":"PACE_COMPARISON",
                "nextRun":"留意后半配速。"}
                """;
    }

    private static String summary() {
        return """
                {"observationEvidenceIds":["OVERALL"],"improvementEvidenceId":"OVERALL","nextRun":"留意平均配速。"}
                """;
    }

    private static RunReviewEvidence evidence(double distanceMeters, long seconds, List<RunReviewEvidence.Split> splits) {
        return new RunReviewEvidence(1, RUN_ID, distanceMeters, seconds, splits);
    }

    private static RunReviewEvidence.Split split(double meters, long seconds, boolean partial) {
        return new RunReviewEvidence.Split(meters, seconds, partial);
    }
}
