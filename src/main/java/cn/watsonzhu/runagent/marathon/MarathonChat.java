package cn.watsonzhu.runagent.marathon;

import java.text.Normalizer;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import cn.watsonzhu.runagent.evidence.AdviceContext;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService.Weather;

import static cn.watsonzhu.runagent.marathon.MarathonData.*;

/** Resolves site chat requests before any model or third-party evidence calls. */
public final class MarathonChat {
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final String CHINESE_NUMBER = "[一二三四五六七八九十]{1,3}";
    private static final String YEAR_TEXT = "(?:19|20|21|22)\\d{2}";
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(" + YEAR_TEXT + ")(?!\\d)");
    private static final Pattern MONTH = Pattern.compile("(?:(?<!\\d)(" + YEAR_TEXT
            + ")\\s*(?:[-/]|年)\\s*)?(?<!\\d)(\\d{1,2}|" + CHINESE_NUMBER + ")\\s*月");
    private static final Pattern ISO_MONTH = Pattern.compile("(?<!\\d)(" + YEAR_TEXT + "-\\d{2})(?![-\\d])");
    private static final Pattern DATE = Pattern.compile("(?:(?<!\\d)(" + YEAR_TEXT + ")\\s*(?:[-/]|年)\\s*)?"
            + "(?<!\\d)(\\d{1,2}|" + CHINESE_NUMBER + ")\\s*(?:月\\s*(\\d{1,2})\\s*[日号]|[-/]\\s*(\\d{1,2})(?!\\d))");
    private static final Pattern DATE_TIME = Pattern.compile(
            "^\\s*(?:t\\s*|[的于]?\\s*)?(凌晨|早上|上午|中午|下午|傍晚|晚上)?\\s*"
                    + "(\\d{1,2})(?::([0-5]\\d)|点(半)?)");
    private static final Pattern RACE_WORD = Pattern.compile("马拉松|半马|全马|跑步赛事|marathon");
    private static final String EVENT_WORD = "(?:路跑|跑步)?(?:比赛|赛事|马拉松|全马|半马)";
    private static final Pattern CALENDAR_LIST = Pattern.compile(
            "(?:有(?:哪些|什么|啥)|哪几场|有哪些热门|推荐.{0,8}|列出|查一下)\\s*" + EVENT_WORD
                    + "|(?:比赛|赛事)(?:日历|赛历)|(?:热门|重点|重量级)(?:的)?(?:比赛|赛事|马拉松|半马|全马)"
                    + "|(?:最近|接下来|近三个月|未来三个月|下个?月|本月|\\d{1,2}\\s*月).{0,18}"
                    + "(?:有|有哪些|有什么).{0,8}" + EVENT_WORD);
    private static final Pattern SCHEDULE = Pattern.compile("赛程|赛历|竞赛规程|开跑|开赛|起跑|开幕|比赛日期|举办"
            + "|报名|截止|倒计时|什么时候|啥时候|何时|哪天|几月几[日号]|还[有剩]几[天周]|多久|几号");
    private static final Pattern OFFICIAL_PODIUM = Pattern.compile("官方成绩|历届成绩|上届成绩|总榜|中国籍榜|国内榜"
            + "|排名|名次|领奖台|冠军|亚军|季军|前三|战报");
    private static final Pattern OFFICIAL_SCHEDULE = Pattern.compile("赛程|赛历|竞赛规程|开跑|开赛|起跑|开幕|比赛日期|举办"
            + "|(?:什么时候|何时|哪天)比赛|报名|倒计时");
    private static final Pattern PODIUM_QUESTION = Pattern.compile("官方成绩|上届成绩|历届成绩|女子组成绩|男子组成绩"
            + "|(?:冠军|亚军|季军|前三).{0,5}(?:是谁|成绩|排名|是哪些)|(?:谁|哪位|哪些人).{0,8}(?:冠军|亚军|季军|前三)");
    private static final Pattern SCHEDULE_QUESTION = Pattern.compile("赛历|赛程|比赛日期|报名截止"
            + "|(?:什么时候|何时|哪天).{0,5}(?:比赛|开跑|开赛|起跑|报名)");
    private static final Pattern WEATHER = Pattern.compile("天气|预报|气温|温度|下雨|降雨|风力|风速|空气质量");
    private static final Pattern ADVICE = Pattern.compile("训练|备赛|减量|补给|碳水|配速|策略|穿什么|怎么穿|建议|装备|书籍"
            + "|原则|准备|热身|怎么练|如何练|提高.{0,6}成绩");
    private static final Pattern PARTICIPATION = Pattern.compile("参加|参赛|准备跑|计划跑|要跑|我.{0,14}跑");
    private static final Pattern TRAINING_WEATHER = Pattern.compile(
            "(?:训练|热身|慢跑|晨跑|夜跑|间歇跑|长跑|跑步)(?:时段|期间|时|当天|前)?的?\\s*(?:天气|预报|气温)"
                    + "|(?:天气|预报|气温).{0,6}(?:适合|能否|可以|是否).{0,6}(?:训练|热身|慢跑|晨跑|夜跑|跑步)");
    private static final Pattern TRAINING_LOCATION = Pattern.compile(
            "(?:我(?:现在)?在|我住在|训练地点[是为]|训练在|在)([\\p{IsHan}]{2,12}?)"
                    + "(?=\\s*(?:[，,；;、。？！?!]|今天|明天|明早|后天|训练|跑步|热身|晨跑|夜跑))");
    private static final Pattern RACE_WEATHER = Pattern.compile(
            "(?:比赛|赛事|开赛|开跑|起跑|赛日)[^，,。？?！!；;\\n]{0,10}(?:天气|预报|气温|下雨|降雨)"
                    + "|(?:天气|预报|气温)[^，,。？?！!；;\\n]{0,10}(?:比赛|开跑|起跑)");
    private static final Pattern EXPLICIT_CITY = Pattern.compile(
            "(?:^|在|查|看|想了解)([\\p{IsHan}]{2,8}(?:省|市|自治区|特别行政区))(?=.{0,16}(?:什么|哪些|赛事|比赛|马拉松))"
                    + "|在([\\p{IsHan}]{2,8}?)(?=附近|有(?:什么|哪些)|下个?月|这个月|\\d{1,2}\\s*月)");
    private static final Pattern DOMESTIC = Pattern.compile("国内(?!外)|中国境内|中国大陆");
    private static final Pattern FEATURED = Pattern.compile("热门|重点|重量级|大满贯");
    private static final Pattern ATHLETE = Pattern.compile(
            "^(?:(?:请|帮我(?:查|看)(?:一下)?|查(?:询|一下)?|想知道|选手|运动员)\\s*)*"
                    + "([\\p{IsHan}·]{2,20}?|[a-z][a-z .'-]{1,79}?)\\s*"
                    + "(?:\\(([a-z\\p{IsHan} ]{2,30})\\)\\s*)?"
                    + "(?:在\\s*)?(?:(?:过去|最?近)(?:两|2)年|最近|近期)?\\s*(?:的)?\\s*"
                    + "(?:(?:有过|拿过|拿了|取得过|有|参加了)\\s*(?:哪[些几]\\s*)?)?"
                    + "(?:已核验的)?(?:马拉松|半马|全马)?\\s*(?:领奖台|上过领奖台|前三名?|冠军)"
                    + "(?:表现|成绩|记录|赛事)?[\\s。？?！!]*$");
    private static final Pattern COMPACT = Pattern.compile("[\\p{Z}\\p{P}\\s]+");
    private static final Pattern MEN = Pattern.compile("\\bmen\\b");
    private static final String FACT_GAP = "[^，,。！？!?；;\\n]";
    private static final String DATE_FACT = "(?:\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|(?:\\d{4}\\s*年\\s*)?\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号])";
    private static final String EVENT_FACT = "(?:马拉松|半马|全马|比赛日期|赛程|开跑|开赛|起跑|举办|鸣枪|报名截止)";
    private static final Pattern ADVICE_RACE_FACTS = Pattern.compile(
            EVENT_FACT + FACT_GAP + "{0,22}" + DATE_FACT + "|" + DATE_FACT + FACT_GAP + "{0,12}" + EVENT_FACT
                    + "|(?:冠军|亚军|季军)\\s*(?:是|为|叫|用时|成绩|以)"
                    + "|(?:官方|总榜|排名|完赛成绩)" + FACT_GAP + "{0,14}(?:第\\s*[一二三123]\\s*名|\\d{1,2}:\\d{2}:\\d{2})"
                    + "|(?:开跑|开赛|起跑)(?:时间)?\\s*(?:是|为|在|于|[:：])?\\s*\\d{1,2}(?::|点)"
                    + "|(?:比赛|起跑|开跑|赛日)" + FACT_GAP + "{0,14}(?:气温|体感|天气|风速|降水概率)\\s*"
                    + "(?:预计|为|是|约)\\s*[-+]?\\d");

    private final MarathonService marathons;
    private final Clock clock;

    public MarathonChat(MarathonService marathons, Clock clock) {
        this.marathons = marathons;
        this.clock = clock;
    }

    public Optional<Reply> query(String message, AdviceContext context, boolean owner) {
        String text = Normalizer.normalize(message, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        boolean weather = matches(WEATHER, text);
        boolean advice = matches(ADVICE, text);
        boolean officialPodium = matches(OFFICIAL_PODIUM, text) && (!advice || matches(PODIUM_QUESTION, text));
        boolean podium = officialPodium || !advice && text.contains("成绩");
        boolean schedule = matches(SCHEDULE, text);
        boolean list = matches(CALENDAR_LIST, text);
        boolean participation = matches(PARTICIPATION, text);
        if (!(podium || weather || advice || schedule || list || participation)) return Optional.empty();

        var names = mentionedRaces(text);
        if (names.size() > 1) {
            return Optional.of(Reply.facts("请指定一个赛事。候选赛事有：" + String.join("、", names) + "。"));
        }
        String race = names.isEmpty() ? null : names.getFirst();
        if (race == null && podium) {
            var athlete = ATHLETE.matcher(text.strip());
            if (athlete.matches() && !matches(RACE_WORD, athlete.group(1)) && !athlete.group(1).contains("谁")) {
                var results = marathons.athlete(athlete.group(1));
                if (athlete.group(2) != null) {
                    String nationality = athlete.group(2).strip();
                    results = results.stream().filter(row -> nationality.equalsIgnoreCase(row.result().nationality())).toList();
                }
                return Optional.of(Reply.facts(MarathonAnswerText.athlete(results)));
            }
        }
        if (race == null && !list) {
            boolean officialSchedule = matches(OFFICIAL_SCHEDULE, text) && (!advice || matches(SCHEDULE_QUESTION, text));
            return matches(RACE_WORD, text) && (officialPodium || officialSchedule)
                    ? Optional.of(Reply.facts("请补充赛事正式名称或简称，以及要查询的届次或项目。"))
                    : Optional.empty();
        }

        String distance = distance(text);
        LocalDate today = LocalDate.now(clock.withZone(CHINA));
        Integer year = year(text, today);
        String podiumText = race != null && podium
                ? MarathonAnswerText.podium(marathons.podium(race, year, category(text), distance, owner,
                        owner && text.contains("刷新")))
                : "";
        if (!podiumText.isEmpty() && !(advice || weather || schedule)) {
            return Optional.of(Reply.facts(podiumText));
        }

        var providedDate = DATE.matcher(text);
        boolean hasDate = providedDate.find();
        String selectedMonth = race != null && hasDate ? null : month(text, today);
        String locality = race == null ? locality(text, context) : null;
        boolean featured = race == null && matches(FEATURED, text);
        CalendarResult calendar = marathons.calendar(locality, selectedMonth, distance, null, race, featured,
                matches(DOMESTIC, text));
        var entries = calendar.events().stream()
                .filter(entry -> race == null || year == null || hasDate || podium || entry.event().year() == year)
                .toList();
        String content = podiumText.isEmpty() ? "" : podiumText + "\n\n";
        content += MarathonAnswerText.calendar(calendar, entries, distance);

        AdviceContext planned = context.withChatHints(message);
        CalendarEntry raceEntry = race != null && entries.size() == 1 ? entries.getFirst() : null;
        var weatherPlan = weatherPlan(text, weather, distance, planned, raceEntry);
        if (raceEntry == null) {
            return Optional.of(new Reply(content, "", advice, weatherPlan.context(), weatherPlan.note()));
        }

        var event = raceEntry.event();
        LocalDate stated = hasDate ? parseDate(providedDate, event.raceDate().getYear()) : null;
        if (stated == null && !weatherPlan.training() && context.runningAt() != null && participation) {
            stated = context.runningAt().toLocalDate();
        }
        if (stated != null && !stated.equals(event.raceDate())) {
            content += "\n\n你提供的参赛日期 " + stated + " 与赛历日期 " + event.raceDate() + " 不一致，请核对届次与项目。";
        }
        String modelContext = "Race edition: " + raceEntry.name() + "; calendar date: " + event.raceDate()
                + "; schedule status: " + event.status() + "; days until event: " + raceEntry.daysUntilRace()
                + "; distances: " + event.distances() + ".";
        return Optional.of(new Reply(content, modelContext, advice, weatherPlan.context(), weatherPlan.note()));
    }

    private List<String> mentionedRaces(String text) {
        String compact = raceText(text);
        var namesByAlias = new LinkedHashMap<String, List<String>>();
        for (var race : marathons.series()) {
            Stream.concat(Stream.of(race.name()), race.aliases().stream()).map(MarathonChat::raceText).distinct()
                    .filter(alias -> alias.length() >= 2)
                    .forEach(alias -> namesByAlias.computeIfAbsent(alias, ignored -> new ArrayList<>()).add(race.name()));
        }
        var hits = new ArrayList<Mention>();
        namesByAlias.forEach((alias, names) -> {
            int start = compact.indexOf(alias);
            while (start >= 0) {
                hits.add(new Mention(names, start, start + alias.length()));
                start = compact.indexOf(alias, start + alias.length());
            }
        });
        hits.sort(Comparator.comparingInt(Mention::start).thenComparing(Comparator.comparingInt(Mention::end).reversed()));
        var names = new LinkedHashSet<String>();
        int longestStart = -1;
        int longestEnd = -1;
        for (var hit : hits) {
            if (hit.end() < longestEnd || hit.end() == longestEnd && hit.start() > longestStart) continue;
            longestStart = hit.start();
            longestEnd = hit.end();
            names.addAll(hit.names());
            if (names.size() >= 8) break;
        }
        return names.stream().limit(8).toList();
    }

    private String locality(String text, AdviceContext context) {
        String compact = compact(text);
        var known = marathons.locations().stream().filter(value -> {
            String alias = compact(value).replaceFirst("(省|市|自治区|特别行政区)$", "");
            return alias.length() >= 2 && compact.contains(alias);
        }).max(Comparator.comparingInt(String::length));
        if (known.isPresent()) return known.get();
        var explicit = EXPLICIT_CITY.matcher(text);
        if (explicit.find()) return explicit.group(1) == null ? explicit.group(2) : explicit.group(1);
        return context.city();
    }

    private static WeatherPlan weatherPlan(String text, boolean requested, String distance, AdviceContext planned,
                                           CalendarEntry race) {
        if (!requested) return new WeatherPlan(AdviceContext.NONE, "", false);
        if (matches(TRAINING_WEATHER, text) && !matches(RACE_WEATHER, text)) {
            var location = TRAINING_LOCATION.matcher(text);
            String city = location.find() ? location.group(1) : planned.city();
            return city == null
                    ? new WeatherPlan(AdviceContext.NONE, "请补充训练地点，以查询对应天气。", true)
                    : new WeatherPlan(new AdviceContext(city, planned.runningAt(), planned.goal(), planned.relativeTime()), "", true);
        }
        if (race == null) {
            return new WeatherPlan(AdviceContext.NONE, "请先确认比赛届次、地点和开跑时间，以查询比赛时段天气。", false);
        }
        var event = race.event();
        Distance project = distance == null
                ? event.distances().size() == 1 ? event.distances().getFirst() : null
                : Distance.valueOf(distance);
        LocalTime start = project == null ? null : event.startTimes().get(project);
        if (start != null && event.status() == EventStatus.CONFIRMED) {
            return new WeatherPlan(new AdviceContext(event.city(), event.raceDate().atTime(start)), "", false);
        }
        LocalDateTime userTime = planned.runningAt() == null ? explicitDateTime(text, event.raceDate().getYear())
                : planned.runningAt();
        if (userTime != null && userTime.toLocalDate().equals(event.raceDate())) {
            return new WeatherPlan(new AdviceContext(event.city(), userTime),
                    "天气查询采用你提供的时间 " + userTime + "。", false);
        }
        return new WeatherPlan(AdviceContext.NONE, "赛事开跑时间尚未确认，请补充具体项目或时间，以查询比赛时段天气。", false);
    }

    private static LocalDateTime explicitDateTime(String text, int defaultYear) {
        var dateMatch = DATE.matcher(text);
        if (!dateMatch.find()) return null;
        var date = parseDate(dateMatch, defaultYear);
        var time = DATE_TIME.matcher(text.substring(dateMatch.end()));
        if (date == null || !time.find()) return null;
        int hour = Integer.parseInt(time.group(2));
        String period = time.group(1);
        if (hour < 12 && period != null && List.of("下午", "傍晚", "晚上").contains(period)) hour += 12;
        int minute = time.group(3) == null ? time.group(4) == null ? 0 : 30 : Integer.parseInt(time.group(3));
        return hour < 24 ? date.atTime(hour, minute) : null;
    }

    private static LocalDate parseDate(Matcher date, int defaultYear) {
        try {
            String day = date.group(3) == null ? date.group(4) : date.group(3);
            return LocalDate.of(date.group(1) == null ? defaultYear : Integer.parseInt(date.group(1)),
                    monthNumber(date.group(2)), Integer.parseInt(day));
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private static Integer year(String text, LocalDate today) {
        var matcher = YEAR.matcher(text);
        if (matcher.find()) return Integer.valueOf(matcher.group(1));
        if (text.contains("去年")) return today.getYear() - 1;
        if (text.contains("今年")) return today.getYear();
        return text.contains("明年") ? today.getYear() + 1 : null;
    }

    private static String month(String text, LocalDate today) {
        var iso = ISO_MONTH.matcher(text);
        if (iso.find()) return iso.group(1);
        var match = MONTH.matcher(text);
        if (match.find()) {
            int number = monthNumber(match.group(2));
            if (number < 1 || number > 12) return "invalid";
            Integer year = match.group(1) == null ? year(text, today) : Integer.valueOf(match.group(1));
            int inferredYear = number < today.getMonthValue() ? today.getYear() + 1 : today.getYear();
            return YearMonth.of(year == null ? inferredYear : year, number).toString();
        }
        if (text.contains("下个月") || text.contains("下月")) return YearMonth.from(today).plusMonths(1).toString();
        return text.contains("本月") || text.contains("这个月") ? YearMonth.from(today).toString() : null;
    }

    private static int monthNumber(String value) {
        if (Character.isDigit(value.charAt(0))) return Integer.parseInt(value);
        int tens = value.indexOf('十');
        String digits = "零一二三四五六七八九";
        if (tens == -1) return value.length() == 1 ? digits.indexOf(value.charAt(0)) : -1;
        if (tens > 1 || value.length() > tens + 2) return -1;
        int prefix = tens == 0 ? 1 : digits.indexOf(value.charAt(0));
        int suffix = value.length() == tens + 1 ? 0 : digits.indexOf(value.charAt(tens + 1));
        return prefix >= 0 && suffix >= 0 ? prefix * 10 + suffix : -1;
    }

    private static String distance(String text) {
        boolean half = text.contains("半马") || text.contains("半程") || text.contains("half marathon");
        boolean full = text.contains("全马") || text.contains("全程") || text.contains("full marathon");
        if (half == full) return null;
        return (half ? Distance.HALF_MARATHON : Distance.MARATHON).name();
    }

    private static String category(String text) {
        boolean women = text.contains("女") || text.contains("women");
        boolean men = text.contains("男") || matches(MEN, text);
        return women == men ? null : women ? "Women" : "Men";
    }

    private static String compact(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return COMPACT.matcher(normalized).replaceAll("");
    }

    private static String raceText(String text) {
        return compact(text).replace("的马拉松", "马拉松").replace("的半马", "半马").replace("的全马", "全马");
    }

    private static boolean matches(Pattern pattern, String text) {
        return pattern.matcher(text).find();
    }

    private record Mention(List<String> names, int start, int end) {}
    private record WeatherPlan(AdviceContext context, String note, boolean training) {}

    public record Reply(String content, String modelContext, boolean adviceRequested, AdviceContext weatherContext,
                        String weatherNote) {
        public boolean allowsAdvice(String advice) {
            return !matches(ADVICE_RACE_FACTS, Normalizer.normalize(advice, Normalizer.Form.NFKC));
        }

        public String weatherSummary(Weather forecast) {
            if (weatherContext.city() == null) return weatherNote;
            return weatherNote.isBlank() ? MarathonAnswerText.weather(forecast)
                    : weatherNote + "\n" + MarathonAnswerText.weather(forecast);
        }

        private static Reply facts(String content) {
            return new Reply(content, "", false, AdviceContext.NONE, "");
        }
    }
}
