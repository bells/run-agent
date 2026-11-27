package cn.watsonzhu.runagent.marathon;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Immutable inverted index of curated public race passages. */
public final class MarathonEvidenceIndex {
    private static final Logger log = LoggerFactory.getLogger(MarathonEvidenceIndex.class);
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_DOCUMENTS = 300;
    private static final int MAX_QUESTION_LENGTH = 512;
    private static final int MAX_MATCHES = 4;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,79}");
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern YEAR = Pattern.compile("(?<![a-z0-9])((?:19|20|21)[0-9]{2})(?![a-z0-9])\\s*年?");
    private static final Pattern WORD = Pattern.compile("\\p{IsHan}+|[\\p{L}\\p{N}&&[^\\p{IsHan}]]+");
    private static final Set<String> COMMON_TERMS = Set.of("马拉", "拉松", "马拉松", "拉松赛", "松赛",
            "赛事", "比赛", "全程", "全马", "半马",
            "哪些", "有哪", "有哪些", "哪里", "什么", "是什", "是什么", "怎么", "怎么样", "怎样", "如何", "一下", "一下子",
            "介绍", "情况", "请问", "资料", "有没有", "有没", "没有", "marathon", "race", "the", "of", "in", "a",
            "an", "on", "and", "at", "for", "about", "what", "where", "when", "how", "was", "is", "did");
    private static final Comparator<Map.Entry<Paragraph, Integer>> BY_RELEVANCE =
            Map.Entry.<Paragraph, Integer>comparingByValue().reversed()
                    .thenComparing(entry -> entry.getKey().match().citation().year(), Comparator.reverseOrder())
                    .thenComparing(entry -> entry.getKey().match().citation().documentId())
                    .thenComparingInt(entry -> entry.getKey().position());

    private final Map<String, List<Posting>> postings;
    private final List<RaceAlias> aliases;

    public MarathonEvidenceIndex(ObjectMapper json, Path corpusPath) {
        var postingsByTerm = new HashMap<String, List<Posting>>();
        var racesByAlias = new HashMap<String, Set<String>>();
        for (Document document : read(json, corpusPath)) {
            var citation = new Citation(document.id(), document.raceId(), document.year(), document.kind(),
                    document.title(), document.publisher(), document.url(), LocalDate.parse(document.publishedAt()),
                    document.distance());
            for (String alias : document.aliases()) {
                racesByAlias.computeIfAbsent(normalize(alias), key -> new HashSet<>()).add(document.raceId());
            }
            Set<String> titleTerms = terms(document.title());
            for (int position = 0; position < document.paragraphs().size(); position++) {
                var paragraph = new Paragraph(new Match(document.paragraphs().get(position), citation), position);
                Set<String> bodyTerms = terms(paragraph.match().text());
                bodyTerms.forEach(term -> addPosting(postingsByTerm, term, paragraph, 3));
                titleTerms.stream().filter(term -> !bodyTerms.contains(term))
                        .forEach(term -> addPosting(postingsByTerm, term, paragraph, 1));
            }
        }
        postingsByTerm.replaceAll((term, hits) -> List.copyOf(hits));
        this.postings = Map.copyOf(postingsByTerm);
        this.aliases = racesByAlias.entrySet().stream()
                .sorted(Map.Entry.<String, Set<String>>comparingByKey(
                        Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder())))
                .map(entry -> new RaceAlias(aliasPattern(entry.getKey()), Set.copyOf(entry.getValue())))
                .toList();
    }

    public boolean available() {
        return !postings.isEmpty();
    }

    public Set<String> raceIdsInQuestion(String question) {
        if (!searchable(question)) return Set.of();
        return Set.copyOf(raceMentions(YEAR.matcher(normalize(question)).replaceAll(" ")).raceIds());
    }

    public List<Match> search(String question) {
        return search(question, null, null);
    }

    public List<Match> search(String question, String raceId, Integer year) {
        return search(question, raceId, year, null, null);
    }

    public List<Match> search(String question, String raceId, Integer year, Kind kind,
                              MarathonData.Distance distance) {
        if (!searchable(question)) return List.of();
        Set<Integer> years = new HashSet<>();
        var yearMatcher = YEAR.matcher(normalize(question));
        while (yearMatcher.find()) {
            years.add(Integer.parseInt(yearMatcher.group(1)));
        }
        if (year != null) years = Set.of(year);

        RaceMentions mentions = raceMentions(yearMatcher.replaceAll(" "));
        Set<String> races = raceId == null ? mentions.raceIds() : Set.of(raceId);
        Set<String> queryTerms = terms(mentions.topic());
        var scores = new HashMap<Paragraph, Integer>();
        for (String term : queryTerms) {
            for (Posting posting : postings.getOrDefault(term, List.of())) {
                Citation citation = posting.paragraph().match().citation();
                if ((races.isEmpty() || races.contains(citation.raceId()))
                        && (years.isEmpty() || years.contains(citation.year()))
                        && (kind == null || kind == citation.kind())
                        && (distance == null || distance == citation.distance())) {
                    scores.merge(posting.paragraph(), posting.weight(), Integer::sum);
                }
            }
        }
        int minimumScore = queryTerms.size() == 1 ? 1 : 2;
        return scores.entrySet().stream().filter(hit -> hit.getValue() >= minimumScore)
                .sorted(BY_RELEVANCE).limit(MAX_MATCHES).map(hit -> hit.getKey().match()).toList();
    }

    private boolean searchable(String question) {
        return available() && question != null && question.length() <= MAX_QUESTION_LENGTH && !question.isBlank();
    }

    private RaceMentions raceMentions(String topic) {
        var races = new HashSet<String>();
        for (RaceAlias alias : aliases) {
            var matcher = alias.pattern().matcher(topic);
            if (matcher.find()) {
                races.addAll(alias.raceIds());
                topic = matcher.replaceAll(" ");
            }
        }
        return new RaceMentions(topic, races);
    }

    private static void addPosting(Map<String, List<Posting>> terms, String term, Paragraph paragraph, int weight) {
        terms.computeIfAbsent(term, key -> new ArrayList<>()).add(new Posting(paragraph, weight));
    }

    private static List<Document> read(ObjectMapper json, Path path) {
        if (path == null) return List.of();
        try {
            if (!Files.isRegularFile(path)) throw new IOException("Missing marathon evidence corpus");
            try (var input = Files.newInputStream(path)) {
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IOException("Marathon evidence corpus is too large");
                String body = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                Corpus corpus = json.readerFor(Corpus.class)
                        .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                        .readValue(body);
                return corpus.documents();
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("Ignoring invalid marathon evidence corpus");
            return List.of();
        }
    }

    private static Set<String> terms(String value) {
        var result = new HashSet<String>();
        var matcher = WORD.matcher(normalize(value));
        while (matcher.find()) {
            String word = matcher.group();
            if (Character.UnicodeScript.of(word.codePointAt(0)) != Character.UnicodeScript.HAN) {
                if (word.length() > 1) result.add(word);
                continue;
            }
            int[] letters = word.codePoints().toArray();
            for (int i = 0; i + 1 < letters.length; i++) {
                result.add(new String(letters, i, 2));
                if (i + 2 < letters.length) result.add(new String(letters, i, 3));
            }
        }
        result.removeAll(COMMON_TERMS);
        return result;
    }

    private static String normalize(String text) {
        return SPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT))
                .replaceAll(" ").strip();
    }

    private static Pattern aliasPattern(String alias) {
        String left = asciiWordCharacter(alias.charAt(0)) ? "(?<![a-z0-9])" : "";
        String right = asciiWordCharacter(alias.charAt(alias.length() - 1)) ? "(?![a-z0-9])" : "";
        return Pattern.compile(left + Pattern.quote(alias) + right);
    }

    private static boolean asciiWordCharacter(char value) {
        return value >= 'a' && value <= 'z' || value >= '0' && value <= '9';
    }

    private static boolean text(String value, int maxLength) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= maxLength
                && value.codePoints().noneMatch(character -> Character.isISOControl(character)
                        || Character.getType(character) == Character.FORMAT);
    }

    private static boolean https(String value) {
        if (!text(value, 2048)) return false;
        URI uri = URI.create(value);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getRawUserInfo() == null;
    }

    public enum Kind { RACE_REPORT, COURSE_GUIDE, INTERVIEW }
    public record Citation(String documentId, String raceId, int year, Kind kind, String title,
                           String publisher, String url, LocalDate publishedAt, MarathonData.Distance distance) { }
    public record Match(String text, Citation citation) { }

    private record RaceAlias(Pattern pattern, Set<String> raceIds) { }
    private record RaceMentions(String topic, Set<String> raceIds) { }
    private record Paragraph(Match match, int position) { }
    private record Posting(Paragraph paragraph, int weight) { }

    private record Corpus(int schemaVersion, List<Document> documents) {
        private Corpus {
            if (schemaVersion != 1 || documents == null || documents.size() > MAX_DOCUMENTS) {
                throw new IllegalArgumentException("Invalid marathon evidence corpus");
            }
            var ids = new HashSet<String>();
            for (Document document : documents) {
                if (document == null || !ids.add(document.id())) {
                    throw new IllegalArgumentException("Duplicate or missing marathon evidence document");
                }
            }
            documents = List.copyOf(documents);
        }
    }

    private record Document(String id, String raceId, int year, Kind kind, String title, String publisher, String url,
                            String publishedAt, List<String> aliases, List<String> paragraphs,
                            MarathonData.Distance distance) {
        private Document {
            if (!text(id, 80) || !ID.matcher(id).matches() || !text(raceId, 80) || !ID.matcher(raceId).matches()
                    || year < 1900 || year > 2199 || kind == null || !text(title, 160) || !text(publisher, 80)
                    || !https(url) || !text(publishedAt, 10) || LocalDate.parse(publishedAt).getYear() < 1900
                    || aliases == null || aliases.isEmpty() || aliases.size() > 12
                    || aliases.stream().anyMatch(alias -> !text(alias, 80) || normalize(alias).length() < 2)
                    || paragraphs == null || paragraphs.isEmpty() || paragraphs.size() > 12
                    || paragraphs.stream().anyMatch(paragraph -> !text(paragraph, 480))) {
                throw new IllegalArgumentException("Invalid marathon evidence document");
            }
            aliases = List.copyOf(aliases);
            paragraphs = List.copyOf(paragraphs);
        }
    }
}
