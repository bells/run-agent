package cn.watsonzhu.runagent.marathon;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import static cn.watsonzhu.runagent.marathon.MarathonData.*;

final class OfficialMarathonEvidence {
    static final int MAX_PROSE_LINES = 18;
    private static final int MAX_TABLE_ROWS = 80;
    private static final int MAX_TABLES = 16;
    private static final int MAX_LEAD_BLOCKS = 8;
    private static final String YEAR = "(?:19|20|21|22)\\d{2}";
    private static final Pattern EDITION = Pattern.compile("(?<![0-9])" + YEAR + "(?![0-9])");
    private static final Pattern NUMERIC_DATE = Pattern.compile("(?<![0-9])(" + YEAR
            + ")(?:年|[-/])([0-9]{1,2})(?:月|[-/])([0-9]{1,2})日?(?![0-9])");
    private static final Pattern CHINESE_MONTH_DAY = Pattern.compile("(?<![0-9])([0-9]{1,2})月([0-9]{1,2})日");
    private static final Pattern ENGLISH_DATE = Pattern.compile(
            "(?i)(?<![0-9])([0-9]{1,2})[ -]([a-z]{3,9})[ -](" + YEAR + ")(?![0-9])");
    private static final Pattern ENGLISH_MONTH_FIRST = Pattern.compile(
            "(?i)(?<![a-z])([a-z]{3,9})[ -]([0-9]{1,2}),? (" + YEAR + ")(?![0-9])");
    private static final Pattern PUBLICATION_DATE = Pattern.compile("(?i)^(?:日期\\s*[:：]|发布时间|更新时间"
            + "|(?:date )?published\\b|last updated\\b)");
    private static final Pattern RACE_DATE_CONTEXT = Pattern.compile("(?i)鸣[枪笛]|开赛|开跑|起跑|举行|举办"
            + "|\\brace (?:date|start|took place)\\b|\\bwas held\\b");
    private static final DateTimeFormatter ENGLISH_SHORT = englishDate("d MMM uuuu");
    private static final DateTimeFormatter ENGLISH_LONG = englishDate("d MMMM uuuu");
    private static final Pattern HALF = Pattern.compile("(?i)\\bhalf[ -]+marathon\\b|半(?:程马拉松|马)|21\\.0975\\s*(?:km|公里)");
    private static final Pattern FULL = Pattern.compile("(?i)\\bmarathon\\b|马拉松|全马|42\\.195\\s*(?:km|公里)");
    private static final Pattern WOMEN = Pattern.compile("(?i)\\bwomen\\b|\\bfemale\\b|女子|女组");
    private static final Pattern MEN = Pattern.compile("(?i)\\bmen\\b|\\bmale\\b|男子|男组");
    private static final Pattern CHINESE_GROUP = Pattern.compile("(?i)\\bchinese (?:nationals|athletes)\\b|中国籍|中国选手|中国运动员");
    private static final Pattern DOMESTIC = Pattern.compile("(?i)\\bdomestic\\b|国内(?:选手|运动员|组|榜)");
    private static final Pattern OTHER_GROUP = Pattern.compile("(?i)\\b(?:wheelchair|masters|age[ -]group|para marathon|u20|u23)\\b"
            + "|年龄组|轮椅|大众组|残疾人组|团体组");
    private static final Pattern NATIONALITY = Pattern.compile("[\\p{L}][\\p{L}.'’ -]{1,35}");
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{M}][\\p{L}\\p{M}.'’·・ -]{0,118}[\\p{L}\\p{M}.]|[\\p{L}]");
    private static final Pattern PLACE = Pattern.compile("(?i)(?:T|=)?([123])[.=]?");
    private static final Pattern ADJACENT_FINAL = Pattern.compile("(?i)(?:women|men)['’]s (?:half )?marathon final");
    private static final Set<String> WITHDRAWN_STATUS = Set.of("dq", "dsq", "disqualified", "取消成绩", "成绩撤销");
    private static final String FINISH = "(?:[0-9]{1,2}:[0-9]{2}:[0-9]{2}|[0-9]{1,2}小时[0-9]{1,2}分[0-9]{1,2}秒)";
    private static final Pattern COLON_TIME = Pattern.compile("([0-9]{1,2}):([0-9]{2}):([0-9]{2})");
    private static final Pattern CHINESE_TIME = Pattern.compile("([0-9]{1,2})小时([0-9]{1,2})分([0-9]{1,2})秒");
    private static final Pattern COURSE_RECORD = Pattern.compile("(?i)\\bcourse record\\b|赛道纪[录錄]|赛会纪[录錄]");
    private static final String ENGLISH_CATEGORY = "(?<category>(?:Women|Men)(?:['’]s)?)";
    private static final String ENGLISH_RANK = "(?<rank>champion|winner|runner-up|first|second|third|third-place finisher)";
    private static final String CHINESE_CATEGORY = "(?<category>[男女]子(?:组)?)";
    private static final String CHINESE_RANK = "(?<rank>冠军|亚军|季军|第一名|第二名|第三名)";
    private static final Pattern ENGLISH_PROSE = Pattern.compile("(?i)^" + ENGLISH_CATEGORY
            + "\\s+(?:(?<domestic>Chinese (?:nationals|athletes))\\s+)?" + ENGLISH_RANK
            + "\\s+(?<name>[^0-9]{1,120}?)\\s+(?:finished|clocked|won)\\s+(?:in |with a time of )?"
            + "(?<time>" + FINISH + ")[.!]?$");
    private static final Pattern CHINESE_PROSE = Pattern.compile("^(?<domestic>中国籍|中国选手)?"
            + CHINESE_CATEGORY + CHINESE_RANK + "(?:为|是|由)?(?<name>[^0-9,。!;]{1,120}?)"
            + "(?:以|,成绩(?:为|是)?|用时)(?<time>" + FINISH + ")(?:的成绩)?(?:夺冠|夺得|完赛|冲线)?[。!]?$");
    private static final Pattern CHINESE_PROSE_REVERSED = Pattern.compile("^(?<name>[^0-9,。!;]{1,120}?)以(?<time>"
            + FINISH + ")(?:的成绩)?(?:获得|夺得|拿下)(?<domestic>中国籍|中国选手)?"
            + CHINESE_CATEGORY + CHINESE_RANK + "[。!]?$");

    private OfficialMarathonEvidence() {}

    static Optional<Page> read(byte[] html, Charset charset, Series series, Event event, int year, Distance distance) {
        try {
            var document = Jsoup.parse(new ByteArrayInputStream(html), charset == null ? null : charset.name(), "");
            document.select("script,style,nav,footer,aside,template,noscript,[hidden],[aria-hidden=true]").remove();
            var body = document.selectFirst("main");
            if (body == null) body = document.selectFirst("article");
            if (body == null) body = document.body();

            var heading = body.selectFirst("h1");
            String title = clean(heading == null ? document.title() : heading.text());
            if (title.length() > 240 || !identifiesSeries(title, series)
                    || !matchesPageProject(title, event, distance)) return Optional.empty();

            var blocks = body.select("h1,h2,h3,h4,h5,h6,table,p,li,time");
            var date = raceDate(title, blocks, year, event);
            if (date.isEmpty()) return Optional.empty();

            var rows = new ArrayList<VerifiedRow>();
            var withdrawals = new ArrayList<WithdrawnRow>();
            var prose = new ArrayList<ProseLine>();
            var headers = new String[6];
            int tables = 0;
            for (var block : blocks) {
                String tag = block.normalName();
                if (tag.matches("h[1-6]")) {
                    int level = tag.charAt(1) - '1';
                    Arrays.fill(headers, level, headers.length, null);
                    headers[level] = clean(block.text());
                } else if (tag.equals("table")) {
                    if (++tables <= MAX_TABLES && rows.size() < MAX_TABLE_ROWS
                            && (block.parent() == null || block.parent().closest("table") == null)) {
                        var caption = block.selectFirst("caption");
                        var label = caption == null ? adjacentFinalLabel(block) : clean(caption.text());
                        var scope = scope(label, headers, title, event);
                        if (scope.distance() == distance && !scope.unsupportedSubgroup()
                                && matchesEdition(scope.labels(), year, date.get())) {
                            var result = tableRows(block, scope, year, date.get());
                            rows.addAll(result.rows());
                            withdrawals.addAll(result.withdrawals());
                        }
                    }
                } else if ((tag.equals("p") || tag.equals("li")) && block.closest("table") == null
                        && prose.size() < MAX_PROSE_LINES) {
                    var scope = scope(null, headers, title, event);
                    if (scope.distance() == distance && matchesEdition(scope.labels(), year, date.get())) {
                        addProse(block, scope, year, date.get(), prose);
                    }
                }
            }
            if (rows.isEmpty() && withdrawals.isEmpty() && prose.isEmpty()) return Optional.empty();
            return Optional.of(new Page(date.get(), rows.stream().limit(MAX_TABLE_ROWS).toList(),
                    withdrawals.stream().limit(MAX_TABLE_ROWS).toList(), List.copyOf(prose)));
        } catch (IOException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static boolean identifiesSeries(String title, Series series) {
        String normalized = normalizeTitle(title);
        return Stream.concat(Stream.of(series.name()), series.aliases().stream()).map(OfficialMarathonEvidence::normalizeTitle)
                .filter(name -> name.length() >= 3).anyMatch(name -> {
                    if (name.matches("[a-z0-9 ]+")) return (" " + normalized + " ").contains(" " + name + " ");
                    return normalized.replace(" ", "").contains(name.replace(" ", ""));
                });
    }

    private static boolean matchesPageProject(String title, Event event, Distance distance) {
        // In a multi-project event the race title identifies the series and the result section proves the project.
        var projects = projectsIn(title);
        return (event != null && event.distances().size() > 1) || projects.isEmpty() || projects.contains(distance);
    }

    private static Optional<LocalDate> raceDate(String title, List<Element> blocks, int year, Event event) {
        var explicitYears = editionYears(title);
        if (explicitYears.stream().anyMatch(value -> value != year)) return Optional.empty();
        var dates = new HashSet<LocalDate>();
        addDates(dates, title, explicitYears.contains(year) ? year : null);
        if (dates.isEmpty()) {
            blocks.stream().takeWhile(block -> !block.normalName().equals("table")).limit(MAX_LEAD_BLOCKS)
                    .filter(block -> !block.normalName().equals("li")).forEach(block -> {
                        String text = clean(block.text());
                        if (text.length() <= 300 && !PUBLICATION_DATE.matcher(text).find()
                                && (block.normalName().matches("h[1-6]") || RACE_DATE_CONTEXT.matcher(text).find())) {
                            addDates(dates, text, explicitYears.contains(year) ? year : null);
                        }
                    });
        }
        if (dates.size() != 1) return Optional.empty();
        var date = dates.iterator().next();
        if (date.getYear() != year && (event == null || !explicitYears.contains(year))) return Optional.empty();
        if (event != null && !date.equals(event.raceDate())) return Optional.empty();
        return Optional.of(date);
    }

    private static boolean matchesEdition(List<String> labels, int year, LocalDate raceDate) {
        return labels.stream().allMatch(label -> {
            var dates = new HashSet<LocalDate>();
            addDates(dates, label, null);
            return dates.stream().allMatch(raceDate::equals)
                    && editionYears(label).stream().allMatch(value -> value == year);
        });
    }

    private static List<Integer> editionYears(String text) {
        String withoutDates = NUMERIC_DATE.matcher(text).replaceAll("");
        withoutDates = ENGLISH_DATE.matcher(withoutDates).replaceAll("");
        withoutDates = ENGLISH_MONTH_FIRST.matcher(withoutDates).replaceAll("");
        return EDITION.matcher(withoutDates).results().map(result -> Integer.parseInt(result.group())).distinct().toList();
    }

    private static void addDates(Set<LocalDate> dates, String text, Integer explicitEdition) {
        var numeric = NUMERIC_DATE.matcher(text);
        while (numeric.find()) addDate(dates, Integer.parseInt(numeric.group(1)),
                Integer.parseInt(numeric.group(2)), Integer.parseInt(numeric.group(3)));
        var english = ENGLISH_DATE.matcher(text);
        while (english.find()) addEnglishDate(dates, english.group(1), english.group(2), english.group(3));
        var monthFirst = ENGLISH_MONTH_FIRST.matcher(text);
        while (monthFirst.find()) addEnglishDate(dates, monthFirst.group(2), monthFirst.group(1), monthFirst.group(3));
        if (explicitEdition != null) {
            var monthDay = CHINESE_MONTH_DAY.matcher(numeric.reset().replaceAll(""));
            while (monthDay.find()) addDate(dates, explicitEdition,
                    Integer.parseInt(monthDay.group(1)), Integer.parseInt(monthDay.group(2)));
        }
    }

    private static void addDate(Set<LocalDate> dates, int year, int month, int day) {
        try {
            dates.add(LocalDate.of(year, month, day));
        } catch (DateTimeException ignored) {
            // Invalid calendar dates cannot prove when the race took place.
        }
    }

    private static void addEnglishDate(Set<LocalDate> dates, String day, String month, String year) {
        var date = day + " " + month + " " + year;
        try {
            dates.add(LocalDate.parse(date, month.length() == 3 ? ENGLISH_SHORT : ENGLISH_LONG));
        } catch (DateTimeException ignored) {
            // The text matched the shape of a date with an invalid month or day.
        }
    }

    private static DateTimeFormatter englishDate(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
                .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }

    private static TableResults tableRows(Element table, Scope scope, int year, LocalDate date) {
        Header columns = null;
        var verified = new ArrayList<VerifiedRow>();
        var withdrawals = new ArrayList<WithdrawnRow>();
        for (var row : table.select("tr").stream().limit(MAX_TABLE_ROWS + 1L).toList()) {
            if (row.closest("table") != table) continue;
            var cells = row.children().stream().filter(cell -> cell.normalName().matches("td|th")).toList();
            if (cells.stream().anyMatch(cell -> structuralSpan(cell, "colspan") || structuralSpan(cell, "rowspan"))) {
                // An embedded heading can change the ranking scope of every following row.
                return new TableResults(List.of(), List.of());
            }
            if (cells.isEmpty() || cells.size() > 24) continue;
            var values = cells.stream().map(Element::text).map(OfficialMarathonEvidence::clean).toList();
            if (cells.stream().allMatch(cell -> cell.normalName().equals("th"))) {
                columns = Header.from(values);
                continue;
            }
            if (columns == null || columns.count() != values.size()) continue;
            var category = columns.category() < 0 ? scope.category() : categoryCell(values.get(columns.category()));
            if (category == null || scope.category() != null && category != scope.category()
                    || columns.distance() >= 0 && !matchesDistance(values.get(columns.distance()), scope.distance())
                    || columns.edition() >= 0 && !matchesRowEdition(values.get(columns.edition()), year, date)) continue;
            var group = new GroupKey(category, scope.subCategory());
            String placeText = values.get(columns.rank());
            String name = values.get(columns.name());
            if (!NAME.matcher(name).matches()) continue;
            String mark = values.get(columns.time());
            if (isWithdrawn(placeText) || isWithdrawn(mark) || isWithdrawn(at(values, columns.status()))) {
                withdrawals.add(new WithdrawnRow(group, name));
                continue;
            }
            var place = PLACE.matcher(placeText);
            String time = finishTime(mark);
            if (!place.matches() || time == null) continue;

            String nationality = at(values, columns.nationality());
            if (!NATIONALITY.matcher(nationality).matches()) nationality = null;
            String recordCell = at(values, columns.record());
            Boolean record = COURSE_RECORD.matcher(recordCell).find() || columns.explicitCourseRecord()
                    && Set.of("yes", "true", "cr", "是").contains(recordCell.toLowerCase(Locale.ROOT)) ? Boolean.TRUE : null;
            verified.add(new VerifiedRow(group, new Rank(Integer.parseInt(place.group(1)), name, nationality, time, record)));
        }
        return new TableResults(verified, withdrawals);
    }

    private static String adjacentFinalLabel(Element table) {
        var container = table;
        for (int depth = 0; depth <= 2 && container != null; depth++, container = container.parent()) {
            var label = container.previousElementSibling();
            if (label == null || label.is("table") || label.selectFirst("table") != null) continue;
            String text = clean(label.text());
            if (ADJACENT_FINAL.matcher(text).matches()) return text;
        }
        return null;
    }

    private static boolean structuralSpan(Element cell, String attribute) {
        return cell.hasAttr(attribute) && !"1".equals(cell.attr(attribute).strip());
    }

    private static boolean matchesDistance(String raw, Distance expected) {
        try {
            return Distance.parse(raw) == expected;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean matchesRowEdition(String value, int year, LocalDate date) {
        if (value.equals(Integer.toString(year)) || value.equals(year + "年")) return true;
        var dates = new HashSet<LocalDate>();
        addDates(dates, value, null);
        return dates.equals(Set.of(date));
    }

    private static boolean isWithdrawn(String value) {
        return WITHDRAWN_STATUS.contains(value.toLowerCase(Locale.ROOT));
    }

    private static void addProse(Element block, Scope scope, int year, LocalDate date, List<ProseLine> prose) {
        var text = clean(block.text());
        if (text.length() > 640 || scope.unsupportedSubgroup()) return;
        for (var sentence : text.split("(?<=[。!?])|(?<=\\.)\\s+(?=\\p{Lu})|,(?=[^\\d,。]{1,120}以[0-9])")) {
            if (prose.size() >= MAX_PROSE_LINES) break;
            var line = sentence.strip();
            var projects = projectsIn(line);
            if (line.isEmpty() || line.length() > 360 || !matchesEdition(List.of(line), year, date)
                    || !projects.isEmpty() && !projects.equals(Set.of(scope.distance()))) continue;
            parseProse(line, scope).ifPresent(row -> prose.add(new ProseLine(prose.size() + 1, line, row)));
        }
    }

    private static Optional<VerifiedRow> parseProse(String line, Scope scope) {
        for (var pattern : List.of(ENGLISH_PROSE, CHINESE_PROSE, CHINESE_PROSE_REVERSED)) {
            var statement = pattern.matcher(line);
            if (!statement.matches()) continue;
            var category = categoryOf(statement.group("category"));
            if (scope.category() != null && scope.category() != category) return Optional.empty();
            String name = clean(statement.group("name"));
            String time = finishTime(statement.group("time"));
            if (category == null || !NAME.matcher(name).matches() || time == null) return Optional.empty();
            var subgroup = statement.group("domestic") == null ? scope.subCategory() : SubCategory.DOMESTIC;
            int rank = switch (statement.group("rank").toLowerCase(Locale.ROOT)) {
                case "champion", "winner", "first", "冠军", "第一名" -> 1;
                case "runner-up", "second", "亚军", "第二名" -> 2;
                default -> 3;
            };
            return Optional.of(new VerifiedRow(new GroupKey(category, subgroup), new Rank(rank, name, null, time, null)));
        }
        return Optional.empty();
    }

    private static Scope scope(String caption, String[] headers, String title, Event event) {
        var labels = new ArrayList<String>();
        if (caption != null) labels.add(caption);
        for (int index = headers.length - 1; index >= 0; index--) {
            if (headers[index] != null) labels.add(headers[index]);
        }
        labels.add(title);

        Set<Distance> projects = Set.of();
        Category category = null;
        boolean chinese = false;
        boolean domestic = false;
        boolean otherGroup = false;
        for (var label : labels) {
            if (projects.isEmpty() && (event == null || event.distances().size() == 1 || !title.equals(label))) {
                projects = projectsIn(label);
            }
            if (category == null) category = categoryOf(label);
            chinese |= CHINESE_GROUP.matcher(label).find();
            domestic |= DOMESTIC.matcher(label).find();
            otherGroup |= OTHER_GROUP.matcher(label).find();
        }
        boolean raceInChina = event != null && Set.of("中国", "CN", "CHN", "China").contains(event.country());
        var distance = projects.size() == 1 ? projects.iterator().next() : null;
        return new Scope(distance, category, chinese || domestic && raceInChina ? SubCategory.DOMESTIC : SubCategory.OVERALL,
                otherGroup || domestic && !chinese && !raceInChina, List.copyOf(labels));
    }

    private static Set<Distance> projectsIn(String text) {
        var half = HALF.matcher(text);
        boolean halfFound = half.find();
        boolean fullFound = FULL.matcher(halfFound ? half.reset().replaceAll(" ") : text).find();
        if (halfFound && fullFound) return Set.of(Distance.HALF_MARATHON, Distance.MARATHON);
        if (halfFound) return Set.of(Distance.HALF_MARATHON);
        return fullFound ? Set.of(Distance.MARATHON) : Set.of();
    }

    private static Category categoryOf(String text) {
        boolean women = WOMEN.matcher(text).find();
        boolean men = MEN.matcher(text).find();
        if (women == men) return null;
        return women ? Category.WOMEN : Category.MEN;
    }

    private static Category categoryCell(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "f", "w", "女" -> Category.WOMEN;
            case "m", "男" -> Category.MEN;
            default -> categoryOf(value);
        };
    }

    static String clean(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("[\\p{Z}\\s]+", " ").strip();
    }

    private static String normalizeTitle(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}]+", " ").replaceAll(" +", " ").strip();
    }

    static String finishTime(String raw) {
        var time = COLON_TIME.matcher(clean(raw));
        if (!time.matches()) {
            time = CHINESE_TIME.matcher(clean(raw));
            if (!time.matches()) return null;
        }
        int hours = Integer.parseInt(time.group(1));
        int minutes = Integer.parseInt(time.group(2));
        int seconds = Integer.parseInt(time.group(3));
        if (minutes >= 60 || seconds >= 60 || hours + minutes + seconds == 0) return null;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private static String at(List<String> values, int column) {
        return column < 0 ? "" : values.get(column);
    }

    private record Header(int count, int rank, int name, int time, int category, int nationality, int record,
                          boolean explicitCourseRecord, int distance, int edition, int status) {
        private static Header from(List<String> cells) {
            var labels = cells.stream().map(OfficialMarathonEvidence::normalizeTitle).toList();
            int rank = uniqueColumn(labels, Set.of("rank", "place", "position", "pos", "排名", "名次"));
            int name = uniqueColumn(labels, Set.of("athlete", "name", "competitor", "athlete name", "姓名", "运动员"));
            int time = uniqueColumn(labels, Set.of("mark", "gun time", "发枪成绩", "枪声成绩", "枪声时间"));
            if (time == -1) {
                time = uniqueColumn(labels, Set.of("time", "finish time", "result", "成绩", "完赛时间"));
                if (time >= 0 && uniqueColumn(labels, Set.of("chip time", "net time", "净计时", "净成绩")) != -1) return null;
            }
            int category = uniqueColumn(labels, Set.of("gender", "sex", "性别"));
            int record = uniqueColumn(labels, Set.of("course record", "record", "赛道纪录", "纪录"));
            int distance = uniqueColumn(labels, Set.of("event", "distance", "discipline", "project", "项目", "距离", "赛程"));
            int edition = uniqueColumn(labels, Set.of("year", "edition", "race date", "年份", "届次", "比赛日期"));
            int status = uniqueColumn(labels, Set.of("status", "result status", "状态", "成绩状态"));
            if (rank < 0 || name < 0 || time < 0 || category == -2 || distance == -2 || edition == -2 || status == -2
                    || uniqueColumn(labels, Set.of("category", "division", "subgroup", "age group", "组别", "年龄组")) != -1) {
                return null;
            }
            return new Header(labels.size(), rank, name, time, category,
                    uniqueColumn(labels, Set.of("nationality", "nat", "国籍")), record,
                    record >= 0 && Set.of("course record", "赛道纪录").contains(labels.get(record)),
                    distance, edition, status);
        }

        private static int uniqueColumn(List<String> cells, Set<String> accepted) {
            int match = -1;
            for (int index = 0; index < cells.size(); index++) {
                if (!accepted.contains(cells.get(index))) continue;
                if (match >= 0) return -2;
                match = index;
            }
            return match;
        }
    }

    record GroupKey(Category category, SubCategory subCategory) {}
    record VerifiedRow(GroupKey group, Rank rank) {}
    record WithdrawnRow(GroupKey group, String athleteName) {}
    record ProseLine(int number, String text, VerifiedRow row) {}
    record Page(LocalDate raceDate, List<VerifiedRow> tableRows, List<WithdrawnRow> withdrawals, List<ProseLine> prose) {}
    private record TableResults(List<VerifiedRow> rows, List<WithdrawnRow> withdrawals) {}
    private record Scope(Distance distance, Category category, SubCategory subCategory,
                         boolean unsupportedSubgroup, List<String> labels) {}
}
