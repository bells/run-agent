package cn.watsonzhu.runagent.marathon;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import cn.watsonzhu.runagent.evidence.RunningEvidenceService.Weather;

import static cn.watsonzhu.runagent.marathon.MarathonData.*;
import static cn.watsonzhu.runagent.marathon.MarathonService.AthleteResult;
import static cn.watsonzhu.runagent.marathon.MarathonService.PodiumResult;

final class MarathonAnswerText {
    private MarathonAnswerText() {}

    static String calendar(CalendarResult calendar, List<CalendarEntry> events, String project) {
        StringBuilder answer = new StringBuilder();
        switch (calendar.status()) {
            case INVALID -> answer.append("赛事查询条件有误。请使用有效的月份、地点及全马或半马项目。");
            case UNAVAILABLE -> answer.append("赛事日历暂不可用。");
            case AMBIGUOUS -> answer.append("请指定一个赛事。候选赛事有：")
                    .append(events.stream().map(CalendarEntry::name).distinct().collect(Collectors.joining("、")))
                    .append("。");
            default -> {
                if (events.isEmpty()) answer.append("在现有赛历和未来三个月的查询范围内没有匹配的赛事。");
                for (var entry : events) appendEvent(answer, entry, project);
                if (calendar.status() == Status.PARTIAL) {
                    answer.append("\n赛历只覆盖部分查询时段，后续可能还有赛事。");
                }
            }
        }
        if (calendar.coverageThrough() != null) answer.append("\n赛历覆盖至 ").append(calendar.coverageThrough()).append("。");
        if (calendar.updatedAt() != null) answer.append(" 数据更新于 ").append(calendar.updatedAt()).append("。");
        return answer.toString().strip();
    }

    private static void appendEvent(StringBuilder answer, CalendarEntry entry, String project) {
        Event event = entry.event();
        answer.append("\n- ").append(event.year()).append(" 届 ").append(entry.name()).append(" · ")
                .append(event.city()).append(" · ").append(event.raceDate())
                .append(event.status() == EventStatus.PLANNED ? "（计划）" : "（已确认）")
                .append(" · ").append(entry.daysUntilRace() == 0 ? "比赛日为今天" : "还有 " + entry.daysUntilRace() + " 天")
                .append("（").append(event.timezone()).append("）");
        for (var distance : event.distances()) {
            if (project != null && !distance.name().equals(project)) continue;
            var start = event.startTimes().get(distance);
            answer.append(" · ").append(distanceName(distance)).append(start == null ? " 开跑时间待公布" : " " + start + " 开跑");
        }
        answer.append(" · 报名截止");
        if (event.regDeadline() == null) {
            answer.append("时间未核验");
        } else {
            long days = entry.daysUntilRegistrationDeadline();
            answer.append(" ").append(event.regDeadline())
                    .append(days < 0 ? "（已过截止）" : days == 0 ? "（今天截止）" : "（还有 " + days + " 天）");
        }
        cite(answer, event.source());
    }

    static String podium(PodiumResult result) {
        var answer = new StringBuilder();
        if (result.selectedYear() != null) answer.append(result.selectedYear()).append(" 届 ");
        if (result.eventName() != null) answer.append(result.eventName()).append(" ");
        if (result.podium() != null) {
            appendPodium(answer, result);
        } else {
            answer.append(switch (result.status()) {
                case AMBIGUOUS -> "请确认要查的赛事。候选赛事有：" + String.join("、", result.candidates()) + "。";
                case INVALID -> "成绩查询条件有误。请核对赛事、届次及项目。";
                case NOT_STARTED -> "尚未开赛，暂未产生正式成绩。";
                case NOT_CACHED -> "本地成绩未收录此届次或组别的已核验官方榜单。";
                case PENDING -> "系统暂未取得可展示的已核验官方成绩。";
                case UNAVAILABLE -> "暂未取得足够的官方成绩证据。";
                default -> "暂无匹配的赛事届次或官方榜单。";
            });
        }
        if (result.unverifiedLaterYear() != null) {
            answer.append("\n").append(result.unverifiedLaterYear()).append(" 届已有赛程，完赛状态仍待核验。");
        }
        return answer.toString().strip();
    }

    private static void appendPodium(StringBuilder answer, PodiumResult result) {
        Podium podium = result.podium();
        answer.append(distanceName(podium.distance())).append("官方领奖台（")
                .append(podium.raceDate()).append("）");
        for (var group : podium.groups()) {
            answer.append("\n").append(groupName(group.category(), group.subCategory()));
            cite(answer, group.source());
            for (var rank : group.results()) {
                answer.append("\n- 第 ").append(rank.rank()).append(" 名 ").append(rank.athleteName())
                        .append(" ").append(rank.finishTime());
                if (rank.nationality() != null) answer.append(" · ").append(rank.nationality());
                if (Boolean.TRUE.equals(rank.isCourseRecord())) answer.append(" · 赛道纪录");
            }
            for (var withdrawal : group.withdrawals()) {
                answer.append("\n- 官方已撤回 ").append(withdrawal.athleteName()).append(" 的该分榜成绩");
                cite(answer, withdrawal.source());
            }
        }
        if (podium.highlights() != null && podium.groups().stream().allMatch(group -> group.withdrawals().isEmpty())) {
            answer.append("\n").append(podium.highlights());
        }
        if (podium.groups().stream().allMatch(group -> group.results().isEmpty())) {
            answer.append("\n当前无有效领奖台成绩。");
        }
        if (result.status() == Status.PARTIAL) answer.append("\n官方已核验结果只覆盖部分前三名。");
        if (podium.groups().stream().noneMatch(group -> group.subCategory() == SubCategory.DOMESTIC)) {
            answer.append("\n当前无可核验的中国籍分榜。");
        }
    }

    static String athlete(List<AthleteResult> results) {
        if (results.isEmpty()) {
            return "现有数据未找到该姓名的已核验领奖台成绩。请核对选手姓名或提供官方榜单姓名。"
                    + "\n覆盖范围为本地已核验的最近两年全马、半马榜单。";
        }
        var nationalities = results.stream().map(row -> row.result().nationality()).filter(Objects::nonNull)
                .collect(Collectors.toCollection(() -> new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
        if (nationalities.size() > 1) {
            return "这个姓名在官方榜单中对应多个国籍记录。请在姓名后用括号补充国籍。候选："
                    + String.join("、", nationalities) + "。";
        }
        var answer = new StringBuilder("已核验的领奖台成绩（最近两年）");
        for (var row : results) {
            answer.append("\n- ").append(row.year()).append(" 届 ").append(row.eventName()).append(" · ")
                    .append(distanceName(row.distance())).append(" · ").append(groupName(row.category(), row.subCategory()))
                    .append(" · ").append(row.result().athleteName()).append(" · 第 ").append(row.result().rank())
                    .append(" 名 · ").append(row.result().finishTime());
            cite(answer, row.source());
        }
        return answer.append("\n覆盖范围为本地已核验的最近两年全马、半马榜单。").toString();
    }

    static String weather(Weather forecast) {
        if (forecast == null) return "暂未取得所问时段的天气预报。";
        return switch (forecast.status()) {
            case READY -> {
                var answer = new StringBuilder(forecast.city()).append(" ").append(forecast.validAt())
                        .append("（").append(forecast.timezone()).append("）天气预报");
                appendWeather(answer, "气温", forecast.temperatureC(), " °C");
                appendWeather(answer, "体感", forecast.apparentTemperatureC(), " °C");
                appendWeather(answer, "降水概率", forecast.precipitationProbability(), "%");
                appendWeather(answer, "风速", forecast.windKph(), " km/h");
                appendWeather(answer, "欧洲 AQI", forecast.europeanAqi(), "");
                yield answer.toString();
            }
            case NO_MATCH -> "天气服务未提供所问地点或时段的有效预报。";
            default -> "暂未取得所问时段的天气预报。";
        };
    }

    private static void appendWeather(StringBuilder answer, String label, Number value, String unit) {
        if (value != null) answer.append(" · ").append(label).append(" ").append(value).append(unit);
    }

    private static void cite(StringBuilder answer, Source source) {
        answer.append(" · 来源 ").append(source.organization()).append("（核验于 ").append(source.verifiedAt())
                .append("） ").append(source.url());
    }

    private static String distanceName(Distance distance) {
        return distance == Distance.HALF_MARATHON ? "半马" : "全马";
    }

    private static String groupName(Category category, SubCategory subCategory) {
        return (category == Category.WOMEN ? "女子" : "男子") + (subCategory == SubCategory.OVERALL ? "总榜" : "中国籍分榜");
    }
}
