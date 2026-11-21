package cn.watsonzhu.runagent.controller;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import cn.watsonzhu.runagent.agent.RunAgentService;
import cn.watsonzhu.runagent.evidence.AdviceContext;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService;
import cn.watsonzhu.runagent.marathon.MarathonChat;
import cn.watsonzhu.runagent.marathon.MarathonService;
import cn.watsonzhu.runagent.marathon.MarathonService.AthleteResult;
import cn.watsonzhu.runagent.marathon.MarathonService.PodiumResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import static cn.watsonzhu.runagent.marathon.MarathonData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MarathonChatControllerTest {
    private static final String TOKEN = "a".repeat(48);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T02:00:00Z"), ZoneOffset.UTC);
    private final RunAgentService agent = mock(RunAgentService.class);
    private final RunningEvidenceService evidence = mock(RunningEvidenceService.class);
    private final MarathonService marathons = mock(MarathonService.class);
    private final WebTestClient client = WebTestClient.bindToController(new PrivateChatController(agent, TOKEN,
            evidence, true, 10, new MarathonChat(marathons, CLOCK))).build();

    @Test
    void verifiedOwnerStatusAloneControlsTheReadOnlyBoundary() {
        when(marathons.series()).thenReturn(List.of(series()));
        var miss = new PodiumResult(Status.NOT_CACHED, null, 2024, List.of(), "重庆半程马拉松");
        when(marathons.podium("重庆半程马拉松", 2024, null, "HALF_MARATHON", false, false)).thenReturn(miss);
        when(marathons.podium("重庆半程马拉松", 2024, null, "HALF_MARATHON", true, true)).thenReturn(miss);

        post("2024年重庆半马谁是冠军？", "invalid").expectStatus().isUnauthorized();
        verifyNoInteractions(marathons);

        post("刷新2024年重庆半马冠军", null).expectStatus().isOk().expectBody()
                .jsonPath("$.content").value(String.class, content -> assertThat(content).contains("2024", "未收录"));
        post("刷新2024年重庆半马冠军", TOKEN).expectStatus().isOk();
        post("刷新2024年重庆半马冠军", TOKEN).expectStatus().isEqualTo(429);

        verify(marathons).podium("重庆半程马拉松", 2024, null, "HALF_MARATHON", false, false);
        verify(marathons).podium("重庆半程马拉松", 2024, null, "HALF_MARATHON", true, true);
        verifyNoInteractions(agent, evidence);
    }

    @Test
    void calendarFactsAndSourcesAreReturnedWithoutModelOrBookCalls() {
        calendar(LocalTime.of(8, 30));
        post("重庆半马12月什么时候开跑？还有几天？", null).expectStatus().isOk().expectBody()
                .jsonPath("$.content").value(String.class, content -> assertThat(content)
                        .contains("2026-12-27", "36 天", "08:30", "https://races.example.org/regulation"))
                .jsonPath("$.evidence").doesNotExist();
        verifyNoInteractions(agent, evidence);
    }

    @Test
    void compoundAdviceUsesOfficialRaceLocationAndTimeAndKeepsAuthoritativeFacts() {
        calendar(LocalTime.of(8, 30));
        String question = "我12月28日参加重庆半马，比赛当天天气怎么样？赛前怎么训练？";
        when(evidence.collect(eq(question), eq(new AdviceContext("重庆", LocalDateTime.parse("2026-12-27T08:30")))))
                .thenReturn(RunningEvidenceService.Bundle.empty());
        when(agent.privateChat(eq(question), contains("2026-12-27"))).thenReturn("赛前可以逐步降低训练量。");

        post(question, TOKEN).expectStatus().isOk().expectBody()
                .jsonPath("$.content").value(String.class, content -> assertThat(content)
                        .contains("赛前可以逐步降低训练量", "2026-12-27", "不一致"));
        verify(evidence).collect(question, new AdviceContext("重庆", LocalDateTime.parse("2026-12-27T08:30")));
    }

    @Test
    void missingRaceStartTimeStillRetrievesBooksWithoutQueryingCurrentWeather() {
        calendar(null);
        String question = "重庆半马比赛天气怎么样，怎么训练？";
        when(evidence.collect(eq(question), eq(AdviceContext.NONE))).thenReturn(RunningEvidenceService.Bundle.empty());
        when(agent.privateChat(eq(question), anyString())).thenReturn("训练建议");

        post(question, null).expectStatus().isOk().expectBody()
                .jsonPath("$.content").value(String.class, content -> assertThat(content).contains("开跑时间"));
        verify(evidence).collect(question, AdviceContext.NONE);
    }

    @Test
    void raceFactsArePreservedWhenTheAdviceInventsAnOfficialDate() {
        calendar(LocalTime.of(8, 30));
        String question = "重庆半马赛前两周怎么训练？";
        when(evidence.collect(eq(question), eq(AdviceContext.NONE))).thenReturn(RunningEvidenceService.Bundle.empty());
        when(agent.privateChat(eq(question), anyString())).thenReturn("重庆半马开跑日期是 2026-12-28。赛前两天跑 5 公里。");

        post(question, null).expectStatus().isOk().expectBody().jsonPath("$.content")
                .value(String.class, content -> assertThat(content).contains("2026-12-27").doesNotContain("2026-12-28"));
    }

    @Test
    void asksWhichAthleteWhenTheSameNameHasConflictingNationalities() {
        var source = new Source("https://race.example.org/result", "测试主办方", CLOCK.instant());
        when(marathons.athlete("同名选手")).thenReturn(List.of(
                new AthleteResult("测试甲马拉松", 2026, Distance.MARATHON, Category.WOMEN, SubCategory.OVERALL,
                        new Rank(1, "同名选手", "CHN", "02:20:00", null), source),
                new AthleteResult("测试乙马拉松", 2026, Distance.MARATHON, Category.WOMEN, SubCategory.OVERALL,
                        new Rank(2, "同名选手", "KEN", "02:21:00", null), source)));

        post("同名选手最近有哪些领奖台成绩？", null).expectStatus().isOk().expectBody().jsonPath("$.content")
                .value(String.class, content -> assertThat(content).contains("CHN", "KEN", "补充")
                        .doesNotContain("02:20:00", "02:21:00"));
        verifyNoInteractions(agent, evidence);
    }

    private void calendar(LocalTime start) {
        var series = series();
        var event = new Event("chongqing_half_2026", series.seriesId(), 2026, LocalDate.parse("2026-12-27"),
                "Asia/Shanghai", "重庆", null, "中国", List.of(Distance.HALF_MARATHON),
                start == null ? Map.of() : Map.of(Distance.HALF_MARATHON, start), null, null,
                EventStatus.CONFIRMED, new Source("https://races.example.org/regulation", "测试主办方", CLOCK.instant()));
        var result = new CalendarResult(Status.READY,
                List.of(new CalendarEntry(event, series.name(), series.aliases(), 36, null)), CLOCK.instant(),
                LocalDate.parse("2027-02-21"));
        when(marathons.series()).thenReturn(List.of(series));
        when(marathons.calendar(isNull(), any(), eq("HALF_MARATHON"), any(), eq(series.name()), eq(false)))
                .thenReturn(result);
    }

    private static Series series() {
        return new Series("chongqing_half", "重庆半程马拉松", List.of("重庆半马"), List.of(), List.of());
    }

    private WebTestClient.ResponseSpec post(String message, String token) {
        var request = client.post().uri("/api/v1/chat").header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.bodyValue(java.util.Map.of("message", message)).exchange();
    }
}
