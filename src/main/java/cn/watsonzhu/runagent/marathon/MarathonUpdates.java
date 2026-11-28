package cn.watsonzhu.runagent.marathon;

import java.math.BigDecimal;
import java.net.URI;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MarathonUpdates {
    static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    static final String EVENT_NAME = "[\\p{IsHan}·]{2,24}(?:马拉松|半马|全马)";
    private static final int MAX_CACHE_ENTRIES = 256;
    private static final Duration POSITIVE_TTL = Duration.ofMinutes(30);
    private static final Duration NEGATIVE_TTL = Duration.ofMinutes(5);
    private static final Pattern EDITION = Pattern.compile("(?<![0-9])(?<![0-9][.,])(?:19|20|21|22)\\d{2}(?![0-9人名个]|[.,][0-9])");
    private static final Pattern NAMED_RACE = Pattern.compile(EVENT_NAME
            + "|\\b(?!(?:The|Full|Half|Wheelchair|Women|Men)\\b)[A-Z][a-z]+(?: [A-Z][a-z]+){0,3} Marathon\\b");
    private static final Pattern GENERIC_PROJECT = Pattern.compile("(?<![\\p{IsHan}·])(?:全程|半程|竞速轮椅)马拉松");
    private static final Pattern PLAN_SENTENCE = Pattern.compile("[。；;！？!?]");
    private static final Pattern NAME = Pattern.compile("[\\p{L}][\\p{L}.'’· -]{1,79}");
    private static final String COUNT_QUALIFIER = "超(?:过)?|约|近|逾|more than|over|about|around|nearly";
    private static final String COUNT_NUMERAL = "(?:[0-9]{1,3}(?:,[0-9]{3}){1,2}|[0-9]{1,9})(?:\\.[0-9]{1,4})?";
    private static final String COUNT = "(?:(?:" + COUNT_QUALIFIER + ")\\s*)?" + COUNT_NUMERAL + "\\s*万?";
    private static final Pattern COUNT_PARTS = Pattern.compile(
            "(?:(" + COUNT_QUALIFIER + ")\\s*)?(" + COUNT_NUMERAL + ")\\s*(万)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PUBLISHED_COUNT = Pattern.compile("(?<![0-9.,])(" + COUNT + ")"
            + "\\s*(?:[人名个]|runners?\\b|applicants?\\b|entries\\b|participants?\\b|places?\\b|registrations?\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CAPACITY_CHANGE = Pattern.compile(
            "((?:赛事|参赛|竞赛|项目)?规模|名额)\\s*(?:也\\s*)?(?:由|从)\\s*"
                    + "(?:(?:往届|上届|去年|[12][0-9]{3}年)的?\\s*)?" + COUNT_NUMERAL + "\\s*万?\\s*[人名个]\\s*"
                    + "(?:提升|扩大|扩容|增加|提高|调整|减少|缩减|扩|增|降)(?:至|到|为)\\s*");
    private static final Pattern FORECAST = Pattern.compile("预计|预期|预计将|有望|计划|将有|expected|will|forecast",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_PARTICIPATION = Pattern.compile(
            "退赛|退出|缺席|未确认|不参加|不会参赛|无缘|未参赛|无意参赛|是否(?:参赛|出战)|尚未确定"
                    + "|传闻|谣传|拟邀请|可能参赛|有望参赛|或将(?:参加|参赛|出战)|待定");
    private static final Pattern TERMS = Pattern.compile("[\\p{P}\\p{Z}\\s]+");
    private static final Pattern METRIC_CLAUSE = Pattern.compile("[。；;！？!?、]|,(?![0-9]{3}(?:[,人名个万\\s。；;！？!?]|$))");
    private static final Pattern MARATHON = Pattern.compile("全程|全马|full marathon", Pattern.CASE_INSENSITIVE);
    private static final Pattern HALF = Pattern.compile("半程|半马|half marathon", Pattern.CASE_INSENSITIVE);
    private static final Logger LOG = LoggerFactory.getLogger(MarathonUpdates.class);

    private final Clock clock;
    private final int visitorLimit;
    private final int ownerLimit;
    private final Function<Query, Lookup> lookup;
    private final Map<Query, Cached> cache = new LinkedHashMap<>();
    private LocalDate budgetDay;
    private int visitorRequests;
    private int ownerRequests;
    private boolean inFlight;

    MarathonUpdates(Clock clock, int visitorLimit, int ownerLimit, Function<Query, Lookup> lookup) {
        if (visitorLimit < 1 || ownerLimit < 1) throw new IllegalArgumentException("Invalid marathon updates quota");
        this.clock = clock;
        this.visitorLimit = visitorLimit;
        this.ownerLimit = ownerLimit;
        this.lookup = lookup;
    }

    String answer(Query query, boolean owner) {
        String cachedOrRejected = admit(query, owner);
        if (cachedOrRejected != null) return cachedOrRejected;

        long started = System.nanoTime();
        try {
            var evidence = select(query, lookup.apply(query));
            String outcome = evidence.isEmpty() ? "NO_EVIDENCE" : "SOURCED";
            String answer = evidence.isEmpty() ? "未查到可核对的" + query.scope() + query.kind.label + "公开资料。"
                    : render(query, evidence);
            remember(query, answer, evidence.isEmpty() ? NEGATIVE_TTL : POSITIVE_TTL);
            logOutcome(query, outcome, started);
            return answer;
        } catch (RuntimeException failure) {
            logOutcome(query, "UNAVAILABLE", started);
            return "赛事动态查询暂时不可用，请稍后再试。";
        } finally {
            release();
        }
    }

    private synchronized String admit(Query query, boolean owner) {
        Instant now = clock.instant();
        cache.values().removeIf(entry -> !now.isBefore(entry.expires()));
        Cached cached = cache.get(query);
        if (cached != null) {
            LOG.info("marathon_updates kind={} status=CACHE_HIT", query.kind());
            return cached.answer();
        }

        LocalDate today = now.atZone(CHINA).toLocalDate();
        if (!today.equals(budgetDay)) {
            budgetDay = today;
            visitorRequests = 0;
            ownerRequests = 0;
        }
        if (owner ? ownerRequests >= ownerLimit : visitorRequests >= visitorLimit) {
            LOG.info("marathon_updates kind={} status=QUOTA", query.kind());
            return owner ? "今天的站主赛事动态查询额度已用完。" : "今天的赛事动态访客查询额度已用完。";
        }
        if (inFlight) {
            LOG.info("marathon_updates kind={} status=BUSY", query.kind());
            return "赛事动态正在查询，请稍后再试。";
        }
        if (owner) ownerRequests++;
        else visitorRequests++;
        inFlight = true;
        return null;
    }

    private synchronized void remember(Query query, String answer, Duration ttl) {
        if (cache.size() >= MAX_CACHE_ENTRIES) cache.remove(cache.keySet().iterator().next());
        cache.put(query, new Cached(answer, clock.instant().plus(ttl)));
    }

    private synchronized void release() {
        inFlight = false;
    }

    private static List<Supported> select(Query query, Lookup lookup) {
        var accepted = new ArrayList<Supported>();
        for (Finding finding : lookup.findings().stream().limit(24).toList()) {
            if (finding == null || finding.fact() == null || finding.fact().kind != query.kind()
                    || finding.source() < 0 || finding.source() >= lookup.sources().size()) continue;
            Source source = lookup.sources().get(finding.source());
            String quote = normalize(finding.quote());
            if (quote.length() < 4 || quote.length() > 300 || !normalize(source.text()).contains(quote)) continue;
            if (!supports(query, source, finding, quote)) continue;
            accepted.add(new Supported(finding, source, source.official(query.officialHosts()), project(quote)));
        }
        if (query.kind() == Kind.ATHLETE && !accepted.isEmpty()) {
            LocalDate first = accepted.stream().map(item -> item.finding().date()).min(LocalDate::compareTo).orElseThrow();
            accepted.removeIf(item -> !first.equals(item.finding().date()));
        }
        return accepted.stream().distinct().sorted(Comparator.comparing(Supported::official).reversed()).toList();
    }

    private static boolean supports(Query query, Source source, Finding finding, String quote) {
        if (query.kind() == Kind.ATHLETE) return supportsPlan(query, source, finding, quote);
        String statement = finding.fact() == Fact.CAPACITY
                ? CAPACITY_CHANGE.matcher(quote).replaceAll("$1为") : quote;
        if (!sameEdition(source.title(), statement, query.year())) return false;
        String project = project(statement);
        if (query.project() != null && !query.project().equals(project)) return false;
        String value = normalize(finding.value());
        return METRIC_CLAUSE.splitAsStream(statement)
                .filter(clause -> bindsRace(query, source.title(), statement, clause))
                .anyMatch(clause -> query.kind() == Kind.REGISTRATION
                        ? supportsCount(finding.fact(), value, clause)
                        : NAME.matcher(value).matches() && clause.contains(value) && !NO_PARTICIPATION.matcher(clause).find()
                            && finding.fact().marker.matcher(clause).find());
    }

    private static boolean supportsCount(Fact fact, String value, String clause) {
        if (!COUNT_PARTS.matcher(value).matches() || !fact.marker.matcher(clause).find()) return false;
        if (fact == Fact.STARTERS && FORECAST.matcher(clause).find()) return false;
        if ((fact == Fact.APPLICATIONS || fact == Fact.REGISTERED) && Fact.CAPACITY.marker.matcher(clause).find()) {
            return false;
        }
        var quantities = PUBLISHED_COUNT.matcher(clause).results().limit(2).toList();
        return quantities.size() == 1 && comparableCount(quantities.getFirst().group(1)).equals(comparableCount(value));
    }

    private static String comparableCount(String value) {
        var parts = COUNT_PARTS.matcher(value);
        if (!parts.matches()) return value;
        var number = new BigDecimal(parts.group(2).replace(",", ""));
        if (parts.group(3) != null) number = number.movePointRight(4);
        String qualifier = parts.group(1) == null ? "" : parts.group(1).toLowerCase(Locale.ROOT);
        return qualifier + ":" + number.stripTrailingZeros().toPlainString();
    }

    private static boolean supportsPlan(Query query, Source source, Finding finding, String quote) {
        LocalDate date = finding.date();
        String race = normalize(finding.race());
        if (date == null || date.isBefore(query.from()) || date.isAfter(query.to())
                || !race.matches(EVENT_NAME)) return false;
        if (!sameEdition(source.title(), quote, date.getYear())) {
            if (hasEdition(source.title()) || hasEdition(quote)) return false;
            String text = normalize(source.text());
            int start = text.indexOf(quote);
            String context = text.substring(Math.max(0, start - 350), Math.min(text.length(), start + quote.length() + 250));
            boolean dated = source.publishedAt() != null && source.publishedAt().getYear() == date.getYear();
            if (!sameEdition("", context, date.getYear()) && (hasEdition(context) || !dated)) return false;
        }
        String matchingDate = "(?<![0-9])0?" + date.getMonthValue() + "\\s*月\\s*0?"
                + date.getDayOfMonth() + "\\s*[日号]";
        Pattern dateInSentence = Pattern.compile(matchingDate + "|" + Pattern.quote(date.toString()));
        return PLAN_SENTENCE.splitAsStream(quote).anyMatch(sentence -> sentence.contains(query.subject())
                && sentence.contains(race) && dateInSentence.matcher(sentence).find()
                && Fact.PLAN.marker.matcher(sentence).results().limit(2).count() == 1
                && !NO_PARTICIPATION.matcher(sentence).find());
    }

    private static boolean bindsRace(Query query, String title, String quote, String clause) {
        if (mentionsRace(query, clause)) return !mentionsAnotherRace(query, clause);
        return mentionsRace(query, title) && !mentionsAnotherRace(query, title) && !mentionsAnotherRace(query, quote);
    }

    private static boolean mentionsRace(Query query, String reference) {
        String text = compact(reference);
        if (longNames(query).map(MarathonUpdates::compact).anyMatch(text::contains)) return true;
        return query.aliases().stream().map(MarathonUpdates::compact).filter(alias -> alias.length() == 2)
                .anyMatch(alias -> Pattern.compile("(?<![0-9])" + query.year() + "年?"
                        + Pattern.quote(alias) + "(?!拉松)").matcher(text).find());
    }

    private static boolean mentionsAnotherRace(Query query, String reference) {
        String rest = normalize(reference);
        for (String name : longNames(query).toList()) {
            rest = rest.replaceAll("(?iu)" + Pattern.quote(normalize(name)), " ");
        }
        return NAMED_RACE.matcher(GENERIC_PROJECT.matcher(rest).replaceAll(" ")).find();
    }

    private static Stream<String> longNames(Query query) {
        return Stream.concat(Stream.of(query.subject()), query.aliases().stream().filter(alias -> compact(alias).length() >= 3));
    }

    private static boolean sameEdition(String title, String quote, int year) {
        String expected = Integer.toString(year);
        var years = EDITION.matcher(quote).results().map(match -> match.group()).distinct().toList();
        if (!years.isEmpty()) return years.size() == 1 && years.getFirst().equals(expected);
        if (quote.contains("上届") || quote.contains("去年")) return false;
        var titleYears = EDITION.matcher(normalize(title)).results().map(match -> match.group()).distinct().toList();
        return titleYears.size() == 1 && titleYears.getFirst().equals(expected);
    }

    private static boolean hasEdition(String text) {
        return EDITION.matcher(normalize(text)).find();
    }

    private static String project(String quote) {
        boolean full = MARATHON.matcher(quote).find();
        boolean half = HALF.matcher(quote).find();
        return full == half ? null : full ? "全马" : "半马";
    }

    private static String render(Query query, List<Supported> evidence) {
        var lines = new LinkedHashMap<String, List<Source>>();
        for (Supported item : evidence) {
            var finding = item.finding();
            String label = finding.fact().label;
            if (query.kind() == Kind.LINEUP && !item.official() && finding.fact() == Fact.CONFIRMED) label = "媒体报道";
            if (query.kind() == Kind.ATHLETE) label = item.official() ? "官方公布" : "媒体报道";
            String project = item.project() == null ? "" : "（" + item.project() + "）";
            String value = query.kind() == Kind.ATHLETE
                    ? finding.date() + " 计划参加" + normalize(finding.race()) : normalize(finding.value());
            if (query.kind() == Kind.REGISTRATION) value += " 人";
            String line = "- **" + label + "**" + project + " · " + value;
            lines.computeIfAbsent(line, ignored -> new ArrayList<>()).add(item.source());
        }
        String result = lines.entrySet().stream().limit(16)
                .map(entry -> entry.getKey() + " · " + entry.getValue().stream().map(Source::citation)
                        .distinct().limit(3).collect(Collectors.joining("、")))
                .collect(Collectors.joining("\n"));
        String note = "";
        if (query.kind() == Kind.REGISTRATION && evidence.stream().noneMatch(item -> item.finding().fact() == Fact.APPLICATIONS)) {
            note = "\n\n本次查到的资料未给出可核对的预报名人数。";
        }
        if (query.kind() == Kind.REGISTRATION && evidence.stream()
                .collect(Collectors.groupingBy(item -> List.of(item.finding().fact().label, String.valueOf(item.project()))))
                .values().stream().anyMatch(items -> items.stream()
                        .map(item -> comparableCount(normalize(item.finding().value()))).distinct().count() > 1)) {
            note += "\n\n同类数字有不同披露，可能对应不同的统计时间或口径。";
        }
        if (query.kind() == Kind.ATHLETE && evidence.stream().map(item -> item.finding().race()).distinct().count() > 1) {
            note = "\n\n公开来源指向不同赛事，暂无法确认最终安排。";
        }
        return "### " + query.scope() + query.kind().label + "\n\n" + result + note;
    }

    static String normalize(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s+", " ").strip();
    }

    private static String compact(String text) {
        return TERMS.matcher(normalize(text).toLowerCase(Locale.ROOT)).replaceAll("");
    }

    enum Kind {
        REGISTRATION("报名与参赛数据", "预报名 报名人数 参赛规模 抽签"),
        LINEUP("精英阵容", "精英选手 特邀 参赛阵容 名单"),
        ATHLETE("参赛计划", "马拉松 参赛 下一站");

        final String label;
        final String searchTerms;

        Kind(String label, String searchTerms) {
            this.label = label;
            this.searchTerms = searchTerms;
        }
    }

    enum Fact {
        APPLICATIONS(Kind.REGISTRATION, "预报名人数", "预报名|报名申请|参与抽签|pre-registration|lottery applicants"),
        REGISTERED(Kind.REGISTRATION, "报名人数", "(?<!预)报名人数|报名者|正式报名|完成报名|registrations? received|registered runners"),
        CAPACITY(Kind.REGISTRATION, "参赛名额或规模", "名额|参赛规模|赛事规模|竞赛规模|扩容|规模(?:为|达|扩至|提升至|扩大至)|限额|人数上限|capacity|places available"),
        STARTERS(Kind.REGISTRATION, "实际参赛人数", "实际参赛|实到|实际起跑|实际出发|actual starters|runners (?:took|crossed) the start"),
        CONFIRMED(Kind.LINEUP, "官方确认", "确认参赛|确认出战|确定参赛|报名参赛|正式公布|首发名单|参赛名单"),
        INVITED(Kind.LINEUP, "受邀", "邀请|受邀|特邀"),
        REPORTED(Kind.LINEUP, "媒体报道", "参赛|出战|领衔|阵容"),
        PLAN(Kind.ATHLETE, "参赛计划", "(?:将|确认|计划|会|确定)(?:去)?(?:参加|出战|参赛)|报名参赛");

        final Kind kind;
        final String label;
        final Pattern marker;

        Fact(Kind kind, String label, String marker) {
            this.kind = kind;
            this.label = label;
            this.marker = Pattern.compile(marker, Pattern.CASE_INSENSITIVE);
        }
    }

    record Query(Kind kind, String subject, int year, LocalDate from, LocalDate to, String project,
                 List<String> aliases, List<String> officialHosts) {
        String scope() {
            return kind == Kind.ATHLETE ? subject + " " : year + " 年" + subject + (project == null ? " " : "（" + project + "）");
        }

        String searchText() {
            String dateOrEdition = kind == Kind.ATHLETE && from.equals(to)
                    ? from.format(DateTimeFormatter.ofPattern("uuuu年M月d日")) : Integer.toString(year);
            if (kind == Kind.ATHLETE && from.getYear() != to.getYear()) dateOrEdition += " " + to.getYear();
            return subject + " " + dateOrEdition + (project == null ? "" : " " + project) + " " + kind.searchTerms;
        }
    }

    record Source(String title, URI url, LocalDate publishedAt, String text) {
        boolean official(List<String> officialHosts) {
            String host = url.getHost().toLowerCase(Locale.ROOT);
            return host.endsWith(".gov.cn") || host.equals("worldathletics.org") || host.equals("www.worldathletics.org")
                    || officialHosts.stream().anyMatch(officialHost -> host.equals(officialHost) || host.endsWith("." + officialHost));
        }

        String citation() {
            String host = url.getHost();
            return "[" + host + "](<" + url.toASCIIString() + ">)"
                    + (publishedAt == null ? "" : " · " + publishedAt);
        }
    }

    record Finding(int source, Fact fact, String value, String race, LocalDate date, String quote) {}
    record Lookup(List<Source> sources, List<Finding> findings) {}
    private record Supported(Finding finding, Source source, boolean official, String project) {}
    private record Cached(String answer, Instant expires) {}

    private static void logOutcome(Query query, String status, long started) {
        LOG.info("marathon_updates kind={} status={} elapsedMs={}", query.kind(), status,
                Duration.ofNanos(System.nanoTime() - started).toMillis());
    }
}
