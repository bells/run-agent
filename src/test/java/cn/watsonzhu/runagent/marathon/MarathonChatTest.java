package cn.watsonzhu.runagent.marathon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;

import cn.watsonzhu.runagent.evidence.AdviceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class MarathonChatTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T02:00:00Z"), ZoneOffset.UTC);
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final MarathonChat chat = new MarathonChat(new MarathonService(new MarathonCalendar(json, null, CLOCK),
            new MarathonPodiumStore(json, null), null, CLOCK), CLOCK);

    @Test
    void qualitativeRaceQuestionsRetrieveOnlyNamedRaceEvidenceAndKeepCitations(@TempDir Path temp) throws Exception {
        Path path = Files.writeString(temp.resolve("race-evidence.json"), """
                {"schemaVersion":1,"documents":[{"id":"fixture-course","raceId":"fixture_marathon","year":2026,
                  "kind":"COURSE_GUIDE","distance":"MARATHON","title":"样例赛道说明","publisher":"样例组委会",
                  "url":"https://example.com/course","publishedAt":"2026-10-24","aliases":["样例马拉松"],
                  "paragraphs":["赛道特点是后段有连续上坡。"]}]}
                """);
        var marathon = new MarathonService(new MarathonCalendar(json, null, CLOCK),
                new MarathonPodiumStore(json, null), null, CLOCK);
        var withCorpus = new MarathonChat(marathon, CLOCK, null, new MarathonEvidenceIndex(json, path));
        var found = withCorpus.query("2026 年样例马拉松赛道有哪些特点？", AdviceContext.NONE, false).orElseThrow();
        assertThat(found.narrativeRequested()).isTrue();
        assertThat(found.content()).contains("后段有连续上坡", "https://example.com/course");
        assertThat(found.modelContext()).contains("后段有连续上坡");
        assertThat(withCorpus.query("样例马拉松的路线介绍", AdviceContext.NONE, false)).isPresent();
        var unknown = withCorpus.query("其他马拉松的赛道特点是什么？", AdviceContext.NONE, false).orElseThrow();
        assertThat(unknown.narrativeRequested()).isFalse();
        assertThat(unknown.content()).doesNotContain("后段有连续上坡", "example.com");
        var interview = withCorpus.query("样例马拉松赛后采访提到哪些赛道特点？", AdviceContext.NONE, false)
                .orElseThrow();
        assertThat(interview.narrativeRequested()).isFalse();
        assertThat(interview.content()).doesNotContain("后段有连续上坡");
        assertThat(withCorpus.query("样例马拉松冠军赛后采访说了什么？", AdviceContext.NONE, false).orElseThrow()
                .content()).contains("主题");
        var half = withCorpus.query("样例马拉松半马的赛道特点？", AdviceContext.NONE, false).orElseThrow();
        assertThat(half.narrativeRequested()).isFalse();
        assertThat(half.content()).doesNotContain("后段有连续上坡");
        var withoutCorpus = new MarathonChat(marathon, CLOCK, null, new MarathonEvidenceIndex(json, null));
        assertThat(withoutCorpus.query("样例马拉松赛道有哪些特点？", AdviceContext.NONE, false).orElseThrow()
                .narrativeRequested()).isFalse();
    }

    @Test
    void findsAthleteAliasInTheExistingLocalResults() {
        var answer = chat.query("阿塞法最近有哪些领奖台成绩？", AdviceContext.NONE, false).orElseThrow();

        assertThat(answer.content()).contains("柏林马拉松", "Tigst Assefa", "最近两年");
        assertThat(answer.adviceRequested()).isFalse();
    }

    @Test
    void chineseMonthAndProvinceFilterTheList() {
        var answer = chat.query("十二月福建省有哪些半马？", AdviceContext.NONE, false).orElseThrow();

        assertThat(answer.content()).contains("厦门海沧半程马拉松", "2026-12-06").doesNotContain("重庆半程马拉松");
    }

    @Test
    void domesticFeaturedQueryFiltersBeforeLimitingTheList() {
        var answer = chat.query("接下来国内有哪些重量级马拉松赛事", AdviceContext.NONE, false).orElseThrow();

        assertThat(answer.content()).contains("上海马拉松", "广州马拉松", "厦门马拉松")
                .doesNotContain("瓦伦西亚马拉松", "檀香山马拉松", "三亚马拉松");
        assertThat(answer.adviceRequested()).isFalse();
    }

    @Test
    void domesticAndOverseasQueryStillIncludesBoth() {
        var answer = chat.query("接下来国内外有哪些重量级马拉松赛事", AdviceContext.NONE, false).orElseThrow();

        assertThat(answer.content()).contains("广州马拉松", "瓦伦西亚马拉松", "檀香山马拉松");
    }

    @Test
    void warnsWhenTheStatedIsoDateDisagreesWithTheOfficialSchedule() {
        var answer = chat.query("我 2026-12-28 跑重庆半马，赛前怎么训练？", AdviceContext.NONE, false).orElseThrow();

        assertThat(answer.content()).contains("2026-12-27", "2026-12-28", "不一致");
    }

    @Test
    void participationStatementUsesRaceCalendarWithoutCallingForAdvice() {
        var answer = chat.query("我12月27号，我要参加重庆半马", AdviceContext.NONE, false).orElseThrow();
        assertThat(answer.content()).contains("重庆半程马拉松", "2026-12-27", "36 天");
        assertThat(answer.adviceRequested()).isFalse();
    }

    @Test
    void separateTrainingWeatherUsesTheUserLocationAndRelativeTime() {
        var answer = chat.query("重庆半马什么时候开跑？我在上海训练，明早8点训练时的天气怎样？",
                AdviceContext.NONE, false).orElseThrow();

        assertThat(answer.weatherContext().city()).isEqualTo("上海");
        assertThat(answer.weatherContext().relativeTime()).isEqualTo(new AdviceContext.RelativeTime(1, LocalTime.of(8, 0)));
    }

    @Test
    void generalTrainingAndWeatherQuestionsRemainInTheExistingFlow() {
        assertThat(chat.query("半马训练有哪些热身动作？我在上海，明早8点天气怎么样？", AdviceContext.NONE, false)).isEmpty();
        assertThat(chat.query("想提高半马成绩，怎么练？", AdviceContext.NONE, false)).isEmpty();
        assertThat(chat.query("第一次跑半马什么时候开始减量？", AdviceContext.NONE, false)).isEmpty();
        assertThat(chat.query("想在半马比赛进前三，应该怎么训练？", AdviceContext.NONE, false)).isEmpty();
    }

    @Test
    void asksForClarificationWhenAnUnknownRaceIsUsedInAnEventFactsQuestion() {
        assertThat(chat.query("海云半马何时开跑？", AdviceContext.NONE, false).orElseThrow().content())
                .contains("赛事正式名称");
        assertThat(chat.query("海云半马冠军是谁？", AdviceContext.NONE, false).orElseThrow().content())
                .contains("赛事正式名称");
    }
}
