package cn.watsonzhu.runagent.marathon;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static cn.watsonzhu.runagent.marathon.MarathonData.*;
import static cn.watsonzhu.runagent.marathon.OfficialMarathonEvidence.*;

public final class OfficialMarathonResultLookup implements MarathonResultLookup {
    private static final URI TAVILY_ENDPOINT = URI.create("https://api.tavily.com/search");
    private static final int MAX_CANDIDATES = 8;
    private static final int MAX_PAGES = 4;
    private static final int MAX_SEARCH_BYTES = 128 * 1024;
    private static final int MAX_HTML_BYTES = 768 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final Logger log = LoggerFactory.getLogger(OfficialMarathonResultLookup.class);
    private static final MediaType XHTML = MediaType.parseMediaType("application/xhtml+xml");

    private final String apiKey;
    private final ObjectMapper json;
    private final WebClient http;
    private final ChatClient extractor;
    private final String extractionPrompt;
    private final Clock clock;
    private final Semaphore modelPermits = new Semaphore(2);

    public OfficialMarathonResultLookup(String apiKey, ObjectMapper json, ChatModel model,
                                        ObservationRegistry observations, Clock clock) {
        this(apiKey, json, model, observations, clock, WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().followRedirect(false)))
                .build());
    }

    OfficialMarathonResultLookup(String apiKey, ObjectMapper json, ChatModel model, ObservationRegistry observations,
                                 Clock clock, WebClient http) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.json = json;
        this.http = http;
        this.extractor = ChatClient.builder(model, observations, null, null).build();
        this.extractionPrompt = loadPrompt();
        this.clock = clock;
    }

    @Override
    public Optional<Podium> lookup(Series series, Event event, int year, Distance distance, long deadlineNanos) {
        if (apiKey.isEmpty() || series.officialHosts().isEmpty() || !withinDeadline(deadlineNanos)) {
            return Optional.empty();
        }
        var hosts = series.officialHosts().stream().filter(OfficialMarathonResultLookup::dnsHost)
                .map(host -> host.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        if (hosts.isEmpty()) return Optional.empty();

        var pages = new ArrayList<SourcedPage>();
        Optional<URI> knownSource = event != null && event.status() == EventStatus.COMPLETED
                ? officialUri(event.source().url(), hosts) : Optional.empty();
        var knownPage = knownSource.flatMap(uri -> fetchPage(uri, series, event, year, distance, deadlineNanos));
        knownPage.ifPresent(pages::add);
        if (knownPage.isEmpty() || !completeTable(knownPage.get().page())) {
            for (URI candidate : candidates(series, year, distance, hosts, deadlineNanos)) {
                if (!withinDeadline(deadlineNanos)) return Optional.empty();
                if (knownSource.filter(candidate::equals).isPresent()) continue;
                fetchPage(candidate, series, event, year, distance, deadlineNanos).ifPresent(pages::add);
            }
        }

        var dates = pages.stream().map(source -> source.page().raceDate()).distinct().toList();
        if (dates.size() != 1 || !withinDeadline(deadlineNanos)) return Optional.empty();
        var groups = new LinkedHashMap<GroupKey, Group>();
        var disputed = new HashSet<GroupKey>();
        pages.forEach(page -> putGroups(groups, disputed, page.page().tableRows(), page.source()));
        // A single extraction can add a missing group from a verified page.
        var prose = pages.stream().filter(page -> page.page().prose().stream()
                .anyMatch(line -> !groups.containsKey(line.row().group()) && !disputed.contains(line.row().group())))
                .findFirst();
        prose.ifPresent(page -> putGroups(groups, disputed, extract(series, year, distance, page.page(), deadlineNanos)
                .stream().filter(row -> !groups.containsKey(row.group())).toList(), page.source()));
        pages.forEach(page -> putWithdrawals(groups, page.page().withdrawals(), page.source()));
        if (groups.isEmpty() || !withinDeadline(deadlineNanos)) return Optional.empty();

        String eventId = event == null ? series.seriesId() + "_" + year : event.eventId();
        return Optional.of(new Podium(1, eventId, series.seriesId(), year, dates.getFirst(), distance,
                List.copyOf(groups.values()), null, clock.instant()));
    }

    private Optional<SourcedPage> fetchPage(URI candidate, Series series, Event event, int year, Distance distance,
                                            long deadline) {
        var html = request(http.get().uri(candidate).accept(MediaType.TEXT_HTML, XHTML),
                MAX_HTML_BYTES, deadline, MediaType.TEXT_HTML, XHTML);
        if (html.isEmpty()) return Optional.empty();
        var page = OfficialMarathonEvidence.read(html.get().bytes(), html.get().type().getCharset(),
                series, event, year, distance).filter(result -> !result.raceDate().isAfter(today(event)));
        String host = candidate.getHost();
        String organization = event != null && event.source().url().equals(candidate.toASCIIString())
                ? event.source().organization() : "worldathletics.org".equalsIgnoreCase(host) ? "World Athletics" : host;
        return page.map(result -> new SourcedPage(result,
                new Source(candidate.toASCIIString(), organization, clock.instant())));
    }

    private static boolean completeTable(Page page) {
        return page.withdrawals().isEmpty() && Arrays.stream(Category.values()).allMatch(category ->
                page.tableRows().stream().filter(row -> row.group().category() == category
                                && row.group().subCategory() == SubCategory.OVERALL)
                        .map(row -> row.rank().rank()).distinct().count() == 3);
    }

    private List<URI> candidates(Series series, int year, Distance distance, Set<String> hosts, long deadline) {
        String names = Stream.concat(Stream.of(series.name()), series.aliases().stream()
                        .filter(alias -> alias.matches("[A-Za-z][A-Za-z .'-]+")).limit(1))
                .distinct().collect(Collectors.joining(" "));
        String project = distance == Distance.HALF_MARATHON ? "半程马拉松 half marathon" : "全程马拉松 marathon";
        var body = Map.of("query", names + " " + year + " " + project + " 官方成绩 official results",
                "max_results", MAX_CANDIDATES, "include_domains", hosts,
                "include_answer", false, "include_raw_content", false, "search_depth", "basic");
        var response = request(http.post().uri(TAVILY_ENDPOINT).headers(headers -> headers.setBearerAuth(apiKey))
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).bodyValue(body),
                MAX_SEARCH_BYTES, deadline, MediaType.APPLICATION_JSON);
        if (response.isEmpty()) return List.of();
        try {
            JsonNode results = json.readTree(response.get().bytes()).path("results");
            if (!results.isArray()) return List.of();
            return results.valueStream().limit(MAX_CANDIDATES)
                    .map(node -> node.path("url").asString(""))
                    .map(url -> officialUri(url, hosts)).flatMap(Optional::stream)
                    .distinct().limit(MAX_PAGES).toList();
        } catch (RuntimeException exception) {
            log.debug("marathon_official_lookup status=INVALID_SEARCH_RESPONSE");
            return List.of();
        }
    }

    private Optional<Payload> request(WebClient.RequestHeadersSpec<?> request, int maxBytes,
                                      long deadline, MediaType... types) {
        if (!withinDeadline(deadline)) return Optional.empty();
        Duration timeout = Duration.ofNanos(Math.min(REQUEST_TIMEOUT.toNanos(), Math.max(1, deadline - System.nanoTime())));
        try {
            return Optional.ofNullable(request.exchangeToMono(response -> {
                var contentType = response.headers().contentType();
                if (response.statusCode() != HttpStatus.OK || response.headers().contentLength().orElse(0) > maxBytes
                        || contentType.isEmpty() || Arrays.stream(types).noneMatch(type -> type.isCompatibleWith(contentType.get()))) {
                    return Mono.empty();
                }
                return DataBufferUtils.join(response.body(BodyExtractors.toDataBuffers()), maxBytes).map(buffer -> {
                    try {
                        var bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return new Payload(bytes, contentType.get());
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                });
            }).timeout(timeout).block());
        } catch (RuntimeException exception) {
            log.debug("marathon_official_lookup status=HTTP_UNAVAILABLE");
            return Optional.empty();
        }
    }

    private List<VerifiedRow> extract(Series series, int year, Distance distance, Page page, long deadline) {
        if (!withinDeadline(deadline) || !modelPermits.tryAcquire()) return List.of();
        String lines = page.prose().stream().map(line -> line.number() + " | " + line.text())
                .collect(Collectors.joining("\n"));
        var task = new FutureTask<>(() -> {
            if (!withinDeadline(deadline)) return null;
            var response = extractor.prompt()
                    .messages(new SystemMessage(extractionPrompt), new UserMessage("赛事 " + series.name()
                            + "\n届次 " + year + "\n项目 " + distance + "\n比赛日期 " + page.raceDate()
                            + "\n官方证据行\n" + lines))
                    .advisors(advisors -> advisors.param(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), false))
                    .options(OpenAiChatOptions.builder().maxTokens(1600))
                    .call().responseEntity(Extraction.class);
            if (response.response() == null || response.response().getResult() == null
                    || !response.response().getResult().getOutput().getToolCalls().isEmpty()) return null;
            return response.entity();
        });
        try {
            Thread.startVirtualThread(() -> {
                try {
                    task.run();
                } finally {
                    modelPermits.release();
                }
            });
        } catch (RuntimeException | Error exception) {
            modelPermits.release();
            throw exception;
        }
        try {
            var result = task.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (result == null || result.entries() == null || result.entries().size() > MAX_PROSE_LINES) return List.of();
            return result.entries().stream().flatMap(candidate -> validated(candidate, page.prose()).stream())
                    .distinct().toList();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException exception) {
            log.debug("marathon_official_lookup status=EXTRACTION_UNAVAILABLE");
        } finally {
            task.cancel(true);
        }
        return List.of();
    }

    private static Optional<VerifiedRow> validated(Candidate candidate, List<ProseLine> prose) {
        if (candidate == null || candidate.evidenceLine() < 1 || candidate.evidenceLine() > prose.size()
                || candidate.athleteName() == null || candidate.athleteName().length() > 120
                || candidate.finishTime() == null || candidate.finishTime().length() > 24) return Optional.empty();
        var row = prose.get(candidate.evidenceLine() - 1).row();
        if (row.group().category() == candidate.category() && row.group().subCategory() == candidate.subCategory()
                && row.rank().rank() == candidate.rank()
                && clean(candidate.athleteName()).equals(row.rank().athleteName())
                && row.rank().finishTime().equals(finishTime(candidate.finishTime()))) {
            return Optional.of(row);
        }
        return Optional.empty();
    }

    private static void putGroups(Map<GroupKey, Group> groups, Set<GroupKey> disputed, List<VerifiedRow> rows, Source source) {
        var byGroup = new LinkedHashMap<GroupKey, List<Rank>>();
        for (var row : rows) byGroup.computeIfAbsent(row.group(), ignored -> new ArrayList<>()).add(row.rank());
        byGroup.forEach((key, values) -> {
            if (disputed.contains(key)) return;
            var ranks = values.stream().distinct().sorted(java.util.Comparator.comparingInt(Rank::rank)).limit(12).toList();
            var existing = groups.get(key);
            if (existing == null) {
                groups.put(key, new Group(key.category(), key.subCategory(), source, ranks));
                return;
            }
            boolean addsEvidence = covers(ranks, existing.results());
            if (addsEvidence && ranks.size() > existing.results().size()) {
                groups.put(key, new Group(key.category(), key.subCategory(), source, ranks));
            } else if (!addsEvidence && !covers(existing.results(), ranks)) {
                disputed.add(key);
                groups.remove(key);
            }
        });
    }

    private static boolean covers(List<Rank> wider, List<Rank> narrower) {
        return narrower.stream().allMatch(previous -> wider.stream().anyMatch(row -> row.rank() == previous.rank()
                && clean(row.athleteName()).equalsIgnoreCase(clean(previous.athleteName()))
                && row.finishTime().equals(previous.finishTime())
                && (row.nationality() == null || previous.nationality() == null
                    || row.nationality().equalsIgnoreCase(previous.nationality()))));
    }

    private static void putWithdrawals(Map<GroupKey, Group> groups, List<WithdrawnRow> rows, Source source) {
        var byGroup = rows.stream().collect(Collectors.groupingBy(WithdrawnRow::group, LinkedHashMap::new,
                Collectors.mapping(row -> new Withdrawal(row.athleteName(), source), Collectors.toList())));
        byGroup.forEach((key, updates) -> {
            var existing = groups.get(key);
            var withdrawn = new LinkedHashMap<String, Withdrawal>();
            if (existing != null) {
                existing.withdrawals().forEach(row -> withdrawn.put(athleteKey(row.athleteName()), row));
            }
            updates.forEach(row -> withdrawn.put(athleteKey(row.athleteName()), row));
            var results = existing == null ? List.<Rank>of() : existing.results().stream()
                    .filter(row -> !withdrawn.containsKey(athleteKey(row.athleteName()))).toList();
            groups.put(key, new Group(key.category(), key.subCategory(), existing == null ? source : existing.source(),
                    results, withdrawn.values().stream().limit(24).toList()));
        });
    }

    private static String athleteKey(String name) {
        return clean(name).toLowerCase(Locale.ROOT);
    }

    private LocalDate today(Event event) {
        return LocalDate.now(event == null ? clock : clock.withZone(ZoneId.of(event.timezone())));
    }

    private static boolean withinDeadline(long deadline) {
        return deadline - System.nanoTime() > 0 && !Thread.currentThread().isInterrupted();
    }

    private static boolean dnsHost(String host) {
        return host != null && host.length() <= 160
                && host.matches("(?i)[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,63}")
                && !host.toLowerCase(Locale.ROOT).endsWith(".localhost");
    }

    private static Optional<URI> officialUri(String url, Set<String> hosts) {
        if (url.length() > 2048) return Optional.empty();
        try {
            var uri = URI.create(url);
            if ("https".equals(uri.getScheme()) && uri.getHost() != null
                    && hosts.contains(uri.getHost().toLowerCase(Locale.ROOT)) && uri.getRawUserInfo() == null
                    && uri.getRawFragment() == null && (uri.getPort() == -1 || uri.getPort() == 443)) {
                return Optional.of(uri);
            }
        } catch (IllegalArgumentException ignored) {
            // The search response is a source of untrusted candidate URLs.
        }
        return Optional.empty();
    }

    private static String loadPrompt() {
        try {
            return new ClassPathResource("prompts/marathon-official-results-extraction.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read marathon result extraction prompt", exception);
        }
    }

    private record SourcedPage(Page page, Source source) {}
    private record Payload(byte[] bytes, MediaType type) {}
    record Extraction(List<Candidate> entries) {}
    record Candidate(int evidenceLine, Category category, SubCategory subCategory, int rank,
                     String athleteName, String finishTime) {}
}
