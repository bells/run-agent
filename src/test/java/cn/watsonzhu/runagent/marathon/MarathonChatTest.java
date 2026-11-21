package cn.watsonzhu.runagent.marathon;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;

import cn.watsonzhu.runagent.evidence.AdviceContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class MarathonChatTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T02:00:00Z"), ZoneOffset.UTC);
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final MarathonChat chat = new MarathonChat(new MarathonService(new MarathonCalendar(json, null, CLOCK),
            new MarathonPodiumStore(json, null), null, CLOCK), CLOCK);

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
