package cn.watsonzhu.runagent.marathon;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import cn.watsonzhu.runagent.agent.RunAgentService;
import cn.watsonzhu.runagent.controller.PrivateChatController;
import cn.watsonzhu.runagent.evidence.AdviceContext;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import static cn.watsonzhu.runagent.marathon.MarathonUpdates.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MarathonUpdatesHttpTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-28T08:00:00Z"), ZoneOffset.UTC);
    private final RunAgentService agent = mock(RunAgentService.class);
    private final RunningEvidenceService evidence = mock(RunningEvidenceService.class);
    private final MarathonService calendar = mock(MarathonService.class);

    @Test
    void anonymousAthleteQuestionUsesShanghaiDateAndSkipsBooksWeatherAndGeneralModel() {
        var captured = new AtomicReference<Query>();
        var updates = new MarathonUpdates(CLOCK, 12, 24, query -> {
            captured.set(query);
            String quote = "张水华表示，11月29日将参加仙游半程马拉松。";
            return new Lookup(List.of(new Source("2026年马拉松赛后报道", URI.create("https://sport.example/runner"), null, quote)),
                    List.of(new Finding(0, Fact.PLAN, null, "仙游半程马拉松", LocalDate.parse("2026-11-29"), quote)));
        });
        var chat = new MarathonChat(calendar, CLOCK, null, null, updates);
        var controller = new PrivateChatController(agent, "a".repeat(48), evidence, true, 10, Optional.of(chat));
        WebTestClient.bindToController(controller).build().post().uri("/api/v1/chat")
                .bodyValue(Map.of("message", "张水华，明天准备参加哪场马拉松？", "context", Map.of("city", "深圳")))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.content").value((String reply) -> assertThat(reply).contains("仙游半程马拉松", "2026-11-29"))
                .jsonPath("$.evidence").doesNotExist();

        assertThat(captured.get().searchText()).contains("张水华", "2026年11月29日").doesNotContain("深圳");
        verifyNoInteractions(agent, evidence);
    }

    @Test
    void recognizesNamedRacesAndDoesNotSendTrainingOrRegistrationDeadlineToSearch() {
        var captured = new AtomicReference<Query>();
        var updates = new MarathonUpdates(CLOCK, 12, 24, query -> {
            captured.set(query);
            return new Lookup(List.of(), List.of());
        });
        when(calendar.series()).thenReturn(List.of(new MarathonData.Series("sh", "上海马拉松", List.of("上马"),
                List.of(), List.of("shang-ma.com"))));
        when(calendar.calendar(nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(Integer.class), nullable(String.class), anyBoolean(), anyBoolean()))
                .thenReturn(new MarathonData.CalendarResult(MarathonData.Status.NO_MATCH, List.of(), null, null));
        var chat = new MarathonChat(calendar, CLOCK, null, null, updates);

        assertThat(chat.query("上马有哪些名将要参加？", AdviceContext.NONE, false)).isPresent();
        assertThat(captured.get()).extracting(Query::subject, Query::kind, Query::year)
                .containsExactly("上海马拉松", Kind.LINEUP, 2026);
        assertThat(chat.query("2025年青溪马拉松报名多少人？", AdviceContext.NONE, false)).isPresent();
        assertThat(captured.get()).extracting(Query::subject, Query::kind, Query::year)
                .containsExactly("青溪马拉松", Kind.REGISTRATION, 2025);
        captured.set(null);
        chat.query("上马报名什么时候截止？", AdviceContext.NONE, false);
        chat.query("我明天跑半马怎么补给？", AdviceContext.NONE, false);
        chat.query("我朋友明天参加哪场马拉松？", AdviceContext.NONE, false);
        chat.query("同事张三明天参加哪场马拉松？", AdviceContext.NONE, false);
        chat.query("我想了解青溪马拉松报名人数", AdviceContext.NONE, false);
        assertThat(captured.get()).isNull();
    }

    @Test
    void usesTheNextCalendarEditionUnlessTheQuestionNamesAYear() {
        var captured = new AtomicReference<Query>();
        var updates = new MarathonUpdates(CLOCK, 12, 24, query -> {
            captured.set(query);
            return new Lookup(List.of(), List.of());
        });
        var series = new MarathonData.Series("test_marathon", "青溪马拉松", List.of("青溪"), List.of(), List.of());
        when(calendar.series()).thenReturn(List.of(series));
        var event = new MarathonData.Event("test_marathon_2027", "test_marathon", 2027, LocalDate.parse("2027-01-09"),
                "Asia/Shanghai", "青溪", null, "中国", List.of(MarathonData.Distance.MARATHON), Map.of(), null,
                null, MarathonData.EventStatus.CONFIRMED,
                new MarathonData.Source("https://race.example.org/schedule", "测试组委会", CLOCK.instant()));
        var entry = new MarathonData.CalendarEntry(event, series.name(), series.aliases(), 42, null);
        when(calendar.calendar(null, null, null, 1, "青溪马拉松", false, false))
                .thenReturn(new MarathonData.CalendarResult(MarathonData.Status.READY, List.of(entry), CLOCK.instant(),
                        event.raceDate()));
        var chat = new MarathonChat(calendar, CLOCK, null, null, updates);

        assertThat(chat.query("本届青溪马拉松报名多少人？", AdviceContext.NONE, false)).isPresent();
        assertThat(captured.get().year()).isEqualTo(2027);
        assertThat(chat.query("2026年青溪马拉松报名多少人？", AdviceContext.NONE, false)).isPresent();
        assertThat(captured.get().year()).isEqualTo(2026);
    }

}
