package cn.watsonzhu.runagent.mcp;

import java.util.List;

import cn.watsonzhu.runagent.agent.RunAgentService;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService.Bundle;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService.Evidence;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService.EvidenceStatus;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService.Knowledge;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService.Source;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"spring.ai.openai.api-key=synthetic-key",
        "run-agent.private-training.enabled=true", "run-agent.private-chat.enabled=true",
        "run-agent.public-api.enabled=true", "run-agent.legacy-api.enabled=false",
        "run-agent.public-api.training-advice-daily-limit=1",
        "run-agent.private-chat.token=" + PrivateTrainingRouteTest.TOKEN})
@Import(PrivateTrainingRouteTest.SyntheticRunning.class)
class PublicTrainingRouteTest {
    private static final String SUMMARY = """
            {"kind":"SUMMARY","startDate":"2026-11-01","endDate":"2026-11-07"}
            """;
    private static final String BEST = """
            {"kind":"PERSONAL_BEST","distanceType":"FIVE_K"}
            """;

    @Autowired ApplicationContext context;
    @MockitoBean RunAgentService agent;
    @MockitoBean RunningEvidenceService evidence;

    @Test
    void anonymousSummarySelectsPublicSnapshotAndTheOwnerKeepsPrivateStatistics() {
        post(SUMMARY, null).expectStatus().isOk().expectBody()
                .jsonPath("$.runCount").isEqualTo(1)
                .jsonPath("$.totalDistanceKm").isEqualTo(5.0)
                .jsonPath("$.dataThrough").isEqualTo("2026-11-03");
        post(SUMMARY, PrivateTrainingRouteTest.TOKEN).expectStatus().isOk().expectBody()
                .jsonPath("$.runCount").isEqualTo(2)
                .jsonPath("$.totalDistanceKm").isEqualTo(12.5)
                .jsonPath("$.dataThrough").isEqualTo("2026-11-10");
    }

    @Test
    void anonymousBestUsesPublicScopeAndAnInvalidTokenNeverSelectsPublicScope() {
        post(BEST, null).expectStatus().isOk().expectBody()
                .jsonPath("$.result.date").isEqualTo("2026-11-03");
        post("{malformed}", "invalid").expectStatus().isUnauthorized();
    }

    @Test
    void rejectsUnmarkedResultsAndClientSelectedSnapshotScope() {
        post("""
                {"kind":"SUMMARY","startDate":"2026-11-08","endDate":"2026-11-09"}
                """, null).expectStatus().isEqualTo(503).expectBody()
                .jsonPath("$.code").isEqualTo("TRAINING_DATA_UNAVAILABLE");
        post("""
                {"kind":"PERSONAL_BEST","distanceType":"FIVE_K","scope":"PRIVATE"}
                """, null).expectStatus().isBadRequest();
    }

    @Test
    void requestedAdviceAddsSafeEvidenceWithoutReplacingTheActualStatistics() {
        var weather = Bundle.empty().evidence().weather();
        var knowledge = new Knowledge(EvidenceStatus.UNAVAILABLE, List.of());
        when(evidence.collect(eq("为十公里训练调整跑量"), any())).thenReturn(new Bundle(new Evidence(knowledge, weather), ""));
        post("""
                {"kind":"SUMMARY","startDate":"2026-11-01","endDate":"2026-11-07",
                 "adviceContext":{"goal":"为十公里训练调整跑量"}}
                """, null).expectStatus().isOk().expectBody()
                .jsonPath("$.runCount").isEqualTo(1)
                .jsonPath("$.evidence.knowledge.status").isEqualTo("UNAVAILABLE")
                .jsonPath("$.evidence.weather.status").isEqualTo("NOT_REQUESTED")
                .jsonPath("$.evidence.promptContext").doesNotExist();
        verifyNoInteractions(agent);
    }

    @Test
    void readyEvidenceProducesAdviceWithoutSendingActivityStatisticsToTheModel() {
        var knowledge = new Knowledge(EvidenceStatus.READY, List.of(new Source("合成训练指南", null)));
        var weather = Bundle.empty().evidence().weather();
        String promptContext = "合成书摘：逐步增加训练量";
        when(evidence.collect(eq("为十公里训练调整跑量"), any()))
                .thenReturn(new Bundle(new Evidence(knowledge, weather), promptContext));
        when(agent.privateChat(contains("为十公里训练调整跑量"), eq(promptContext)))
                .thenReturn("逐步增加训练量，并安排恢复日。");

        String request = """
                {"kind":"SUMMARY","startDate":"2026-11-01","endDate":"2026-11-07",
                 "adviceContext":{"goal":"为十公里训练调整跑量"}}
                """;
        post(request, null).expectStatus().isOk().expectBody()
                .jsonPath("$.runCount").isEqualTo(1)
                .jsonPath("$.evidence.knowledge.sources[0].title").isEqualTo("合成训练指南")
                .jsonPath("$.advice").isEqualTo("逐步增加训练量，并安排恢复日。");
        post(request, null).expectStatus().isOk().expectBody()
                .jsonPath("$.runCount").isEqualTo(1)
                .jsonPath("$.evidence.knowledge.sources[0].title").isEqualTo("合成训练指南")
                .jsonPath("$.advice").doesNotExist();
        verify(agent).privateChat(contains("为十公里训练调整跑量"), eq(promptContext));
    }

    private WebTestClient.ResponseSpec post(String body, String token) {
        var request = WebTestClient.bindToApplicationContext(context).build().post()
                .uri("/api/v1/training/stats").header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.bodyValue(body).exchange();
    }
}
