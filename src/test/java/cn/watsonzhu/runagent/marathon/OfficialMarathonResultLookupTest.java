package cn.watsonzhu.runagent.marathon;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import static cn.watsonzhu.runagent.marathon.MarathonData.*;
import static org.assertj.core.api.Assertions.assertThat;

class OfficialMarathonResultLookupTest {
    private static final String SOURCE = "https://official.example/results";
    private static final String SEARCH = "https://api.tavily.com/search";
    private static final LocalDate RACE_DATE = LocalDate.of(2025, 9, 21);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T04:00:00Z"), ZoneOffset.UTC);
    private static final Series SERIES = new Series("sample", "Sample Marathon",
            List.of("样例马拉松"), List.of(), List.of("official.example"));
    private static final String TABLE = """
            <h2>Women's Marathon</h2>
            <table>
              <tr><th>Place</th><th>Name</th><th>Nat.</th><th>Mark</th><th>Chip Time</th></tr>
              <tr><td>1.</td><td>Alice North</td><td>CAN</td><td>2:20:18</td><td>2:20:13</td></tr>
              <tr><td>2</td><td>Bea East</td><td></td><td>2:22:59</td><td>2:22:54</td></tr>
              <tr><td>2</td><td>Carey Lake</td><td></td><td>2:22:59</td><td>2:22:56</td></tr>
              <tr><td>4</td><td>Dana West</td><td></td><td>2:25:00</td><td>2:25:00</td></tr>
            </table>
            """;

    @Test
    void selectsOfficialTimingColumnAndPreservesTiesWithoutModel() {
        var seen = new ArrayList<URI>();
        var result = lookup(seen, Map.of(SOURCE, page("2025 Sample Marathon — 21 SEP 2025", TABLE)),
                List.of("http://official.example/results", "https://official.example.evil/results",
                        "https://official.example@evil.example/results", "https://official.example:8443/results", SOURCE),
                unexpectedModel()).lookup(SERIES, event(), 2025, Distance.MARATHON, deadline());

        var podium = result.orElseThrow();
        assertThat(seen).containsExactly(URI.create(SEARCH), URI.create(SOURCE));
        assertThat(podium.raceDate()).isEqualTo(RACE_DATE);
        assertThat(podium.groups()).singleElement().satisfies(group -> {
            assertThat(group.category()).isEqualTo(Category.WOMEN);
            assertThat(group.subCategory()).isEqualTo(SubCategory.OVERALL);
            assertThat(group.source().url()).isEqualTo(SOURCE);
            assertThat(group.results()).containsExactly(
                    new Rank(1, "Alice North", "CAN", "02:20:18", null),
                    new Rank(2, "Bea East", null, "02:22:59", null),
                    new Rank(2, "Carey Lake", null, "02:22:59", null));
        });
    }

    @Test
    void rejectsOtherRaceEditionDateAndDistance() {
        var badSources = Map.of(SOURCE, page("2025 Other Marathon — 21 SEP 2025", TABLE),
                "https://official.example/old", page("2024 Sample Marathon — 29 SEP 2024", TABLE),
                "https://official.example/date", page("2025 Sample Marathon — 22 SEP 2025", TABLE),
                "https://official.example/half", page("2025 Sample Half Marathon — 21 SEP 2025",
                        TABLE.replace("Women's Marathon", "Women's Half Marathon")));
        var result = lookup(new ArrayList<>(), badSources, List.copyOf(badSources.keySet()), unexpectedModel())
                .lookup(SERIES, event(), 2025, Distance.MARATHON, deadline());

        assertThat(result).isEmpty();
    }

    @Test
    void doesNotTreatHistoricalTableInResultsArchiveAsCurrentEdition() {
        String archive = "<h2>2024 results archive</h2>"
                + TABLE.replace("h2", "h3");
        var result = lookup(new ArrayList<>(), Map.of(SOURCE, page("2025 Sample Marathon — 21 SEP 2025", archive)),
                List.of(SOURCE), unexpectedModel()).lookup(SERIES, event(), 2025, Distance.MARATHON, deadline());

        assertThat(result).isEmpty();
    }

    @Test
    void ageGroupRankingDoesNotBecomeTheOverallPodium() {
        String official = page("2025 Sample Marathon — 21 SEP 2025",
                TABLE.replace("Women's Marathon", "Women's Marathon Age Group 40-44"));
        var result = lookup(new ArrayList<>(), Map.of(SOURCE, official), List.of(SOURCE), unexpectedModel())
                .lookup(SERIES, event(), 2025, Distance.MARATHON, deadline());

        assertThat(result).isEmpty();
    }

    @Test
    void declinesRowsWithContradictoryProjectYearOrEmbeddedGender() {
        var incompatible = List.of(
                "<tr><th>Place</th><th>Name</th><th>Mark</th><th>Distance</th></tr>"
                        + "<tr><td>1</td><td>Test Runner</td><td>2:04:33</td><td>Half Marathon</td></tr>",
                "<tr><th>Place</th><th>Name</th><th>Mark</th><th>Edition</th></tr>"
                        + "<tr><td>1</td><td>Test Runner</td><td>2:04:33</td><td>2024</td></tr>",
                "<tr><th>Place</th><th>Name</th><th>Mark</th></tr>"
                        + "<tr><th colspan='3'>Women</th></tr>"
                        + "<tr><td>1</td><td>Test Runner</td><td>2:04:33</td></tr>");
        for (var rows : incompatible) {
            var official = page("2025 Sample Marathon — 21 SEP 2025", "<h2>Men's Marathon</h2><table>" + rows + "</table>");
            var result = lookup(new ArrayList<>(), Map.of(SOURCE, official), List.of(SOURCE), unexpectedModel())
                    .lookup(SERIES, event(), 2025, Distance.MARATHON, deadline());
            assertThat(result).isEmpty();
        }
    }

    @Test
    void readsAWithdrawalOnlyFromAnExplicitOfficialStatus() {
        var official = page("2025 Sample Marathon — 21 SEP 2025", """
                <h2>Women's Marathon</h2><table>
                  <tr><th>Place</th><th>Name</th><th>Mark</th><th>Status</th></tr>
                  <tr><td>1</td><td>Alice North</td><td>2:20:18</td><td>DSQ</td></tr>
                </table>
                """);
        var result = lookup(new ArrayList<>(), Map.of(SOURCE, official), List.of(SOURCE), unexpectedModel())
                .lookup(SERIES, event(), 2025, Distance.MARATHON, deadline());
        assertThat(result.orElseThrow().groups()).singleElement().satisfies(group -> {
            assertThat(group.results()).isEmpty();
            assertThat(group.withdrawals()).singleElement().satisfies(withdrawal -> {
                assertThat(withdrawal.athleteName()).isEqualTo("Alice North");
                assertThat(withdrawal.source().url()).isEqualTo(SOURCE);
            });
        });
    }

    @Test
    void conflictingOfficialPagesDoNotPublishEitherWinner() {
        String other = "https://official.example/second";
        String title = "2025 Sample Marathon — 21 SEP 2025";
        var result = lookup(new ArrayList<>(),
                Map.of(SOURCE, page(title, TABLE), other, page(title, TABLE.replace("Alice North", "Casey Lake"))),
                List.of(SOURCE, other), unexpectedModel()).lookup(SERIES, event(), 2025, Distance.MARATHON, deadline());
        assertThat(result).isEmpty();
    }

    @Test
    void handlesCompetitionResultsWithSeparateDateAndUnlabeledCountryColumn() {
        String official = page("Sample Marathon", """
                <h2>Sample (CAN)</h2><h3>21 SEP 2025</h3><h4>Competition category</h4>
                <h2>Women's Marathon</h2><h3>Final</h3>
                <table>
                  <tr><th colspan="1">Place</th><th colspan="1">Name</th><th colspan="1">Birth Date</th>
                    <th colspan="1"></th><th colspan="1">Mark</th></tr>
                  <tr><td>1.</td><td>Alice North</td><td>12 May 1998</td><td>CAN</td><td>2:20:18</td></tr>
                </table>
                """);
        var result = lookup(new ArrayList<>(), Map.of(SOURCE, official), List.of(SOURCE), unexpectedModel())
                .lookup(SERIES, null, 2025, Distance.MARATHON, deadline());

        assertThat(result.orElseThrow().raceDate()).isEqualTo(RACE_DATE);
        assertThat(result.orElseThrow().groups()).singleElement()
                .satisfies(group -> assertThat(group.results())
                        .containsExactly(new Rank(1, "Alice North", null, "02:20:18", null)));
    }

    @Test
    void acceptsExactProjectAndCategoryInTheTablesAdjacentLabel() {
        String official = page("2025 Sample Marathon — 21 SEP 2025", """
                <section>
                  <div>Women's Marathon Final</div>
                  <div><div><table>
                    <tr><th>Pos.</th><th>Athlete</th><th>Mark</th></tr>
                    <tr><td>1.</td><td>Alice North</td><td>2:20:18</td></tr>
                  </table></div></div>
                </section>
                """);
        var result = lookup(new ArrayList<>(), Map.of(SOURCE, official), List.of(SOURCE), unexpectedModel())
                .lookup(SERIES, event(), 2025, Distance.MARATHON, deadline());

        assertThat(result.orElseThrow().groups()).singleElement().satisfies(group -> {
            assertThat(group.category()).isEqualTo(Category.WOMEN);
            assertThat(group.results()).containsExactly(new Rank(1, "Alice North", null, "02:20:18", null));
        });
    }

    @Test
    void readsBothVerifiedPodiumsFromKnownCompletedEditionSourceBeforeSearching() {
        var seen = new ArrayList<URI>();
        String womensTable = TABLE.replace("<tr><td>2</td><td>Carey", "<tr><td>3</td><td>Carey");
        String mensTable = womensTable.replace("Women's", "Men's");
        var official = page("2025 Sample Marathon — 21 SEP 2025", womensTable + mensTable);

        var result = lookup(seen, Map.of(SOURCE, official), List.of(), unexpectedModel())
                .lookup(SERIES, event(EventStatus.COMPLETED), 2025, Distance.MARATHON, deadline());

        assertThat(seen).containsExactly(URI.create(SOURCE));
        assertThat(result.orElseThrow().groups()).extracting(Group::category)
                .containsExactlyInAnyOrder(Category.MEN, Category.WOMEN);
    }

    @Test
    void usesRaceDateFromLeadOverArticlePublicationDate() {
        String official = page("2025样例马拉松成功举办", """
                <p>日期：2025-09-22</p>
                <p>9月21日上午9时，2025样例马拉松鸣笛开赛。</p>
                <p>男子组冠军由张三以2小时04分25秒的成绩夺得，李四以2小时05分50秒获得亚军。</p>
                """);
        var result = lookup(new ArrayList<>(), Map.of(SOURCE, official), List.of(SOURCE), prompt -> {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                    {"entries":[
                      {"evidenceLine":1,"category":"MEN","subCategory":"OVERALL","rank":1,"athleteName":"张三","finishTime":"02:04:25"}
                    ]}
                    """))));
        }).lookup(SERIES, null, 2025, Distance.MARATHON, deadline());

        assertThat(result.orElseThrow().raceDate()).isEqualTo(RACE_DATE);
        assertThat(result.orElseThrow().groups()).singleElement()
                .satisfies(group -> assertThat(group.results()).containsExactly(new Rank(1, "张三", null, "02:04:25", null)));
    }

    @Test
    void rejectsValidLookingTimesAndRanksTakenFromAnotherEvidenceLine() {
        var official = page("2025 Sample Marathon — 21 September 2025", """
                <p>Women's champion Alice North finished in 2:20:18.</p>
                <p>Women's runner-up Bea East finished in 2:22:59.</p>
                <p>Men's champion Casey Lake finished in 2:04:25.</p>
                """);
        var extraction = """
                {"entries":[
                  {"evidenceLine":1,"category":"WOMEN","subCategory":"OVERALL","rank":1,"athleteName":"Alice North","finishTime":"2:22:59"},
                  {"evidenceLine":2,"category":"WOMEN","subCategory":"OVERALL","rank":1,"athleteName":"Bea East","finishTime":"2:22:59"},
                  {"evidenceLine":3,"category":"WOMEN","subCategory":"DOMESTIC","rank":1,"athleteName":"Casey Lake","finishTime":"2:04:25"},
                  {"evidenceLine":3,"category":"MEN","subCategory":"OVERALL","rank":1,"athleteName":"Casey Lake","finishTime":"02:04:25"}
                ]}
                """;
        var result = lookup(new ArrayList<>(), Map.of(SOURCE, official), List.of(SOURCE), prompt -> {
            assertThat(prompt.getUserMessage().getText()).contains("Casey Lake").doesNotContain("Alice the snippet");
            return new ChatResponse(List.of(new Generation(new AssistantMessage(extraction))));
        }).lookup(SERIES, null, 2025, Distance.MARATHON, deadline());

        assertThat(result.orElseThrow().raceDate()).isEqualTo(RACE_DATE);
        assertThat(result.orElseThrow().groups()).singleElement().satisfies(group -> {
            assertThat(group.category()).isEqualTo(Category.MEN);
            assertThat(group.subCategory()).isEqualTo(SubCategory.OVERALL);
            assertThat(group.results()).containsExactly(new Rank(1, "Casey Lake", null, "02:04:25", null));
        });
    }

    @Test
    void ignoresRedirectsAndUnverifiedSearchSnippets() {
        var seen = new ArrayList<URI>();
        var result = lookup(seen, Map.of(), List.of(SOURCE), unexpectedModel())
                .lookup(SERIES, event(), 2025, Distance.MARATHON, deadline());

        assertThat(result).isEmpty();
        assertThat(seen).containsExactly(URI.create(SEARCH), URI.create(SOURCE));
    }

    @Test
    void expiredDeadlineDoesNotCallExternalProviders() {
        var seen = new ArrayList<URI>();
        var result = lookup(seen, Map.of(SOURCE, "unused"), List.of(SOURCE), unexpectedModel())
                .lookup(SERIES, event(), 2025, Distance.MARATHON, System.nanoTime() - 1);

        assertThat(result).isEmpty();
        assertThat(seen).isEmpty();
    }

    @Test
    void cancelsAStalledOfficialFetchAtSharedDeadline() {
        var cancelled = new AtomicBoolean();
        var client = WebClient.builder().exchangeFunction(request -> {
            if (SEARCH.equals(request.url().toString())) {
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"results\":[{\"url\":\"" + SOURCE + "\"}]}").build());
            }
            return Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true));
        }).build();
        var lookup = new OfficialMarathonResultLookup("synthetic-test-key", JsonMapper.builder().build(),
                unexpectedModel(), ObservationRegistry.NOOP, CLOCK, client);

        assertThat(lookup.lookup(SERIES, event(), 2025, Distance.MARATHON,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(1))).isEmpty();
        assertThat(cancelled).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"verified", "changed-image", "chip-time", "bad-row"})
    void readsOnlyVerifiedGunTimeRowsFromRegisteredOfficialImage(String caseName) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1000, 600, BufferedImage.TYPE_INT_RGB), "png", bytes);
        String sha256 = caseName.equals("changed-image") ? "0".repeat(64)
                : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        URI noticePage = URI.create("https://official.example/podium-notice");
        URI attachment = URI.create("https://cdn.example/image/verified.png");
        var board = new OfficialMarathonImageNotice.Board(Category.MEN, SubCategory.DOMESTIC, 0, 600);
        var notice = new OfficialMarathonImageNotice.Notice("sample_2025", RACE_DATE, Distance.MARATHON,
                noticePage, "Sample Organizer", List.of(
                new OfficialMarathonImageNotice.Attachment(attachment, sha256, 1000, 600, List.of(board))));
        var imageNotice = new OfficialMarathonImageNotice(List.of(notice), (image, subgroup, deadline) -> List.of(
                word(75, 110, "名次"), word(500, 110, "姓名"),
                word(850, 110, caseName.equals("chip-time") ? "净计时成绩" : "枪声成绩"),
                word(75, 180, "1"), word(500, 180, "张勇"), word(850, 180, "02:12:35"),
                word(75, 240, "2"), word(500, 242, "王东"), word(850, 237, "02:13:48"),
                word(75, 310, "3"), word(500, 306, "李成"),
                word(850, 311, caseName.equals("bad-row") ? "02:14:95" : "02:14:55"),
                word(75, 400, "4"), word(500, 402, "刘力"), word(850, 401, "02:15:03")));
        var fetched = new ArrayList<URI>();
        var client = WebClient.builder().exchangeFunction(request -> {
            var url = request.url();
            fetched.add(url);
            if (url.equals(noticePage)) {
                return Mono.just(ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, "text/html")
                        .body(page("成绩公示", "<img src='https://untrusted.example/other.png'>"
                                + "<img src='//cdn.example/image/verified.png'>"))
                        .build());
            }
            assertThat(url).isEqualTo(attachment);
            assertThat(request.headers().getFirst(HttpHeaders.REFERER)).isEqualTo(noticePage.toString());
            assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
            return Mono.just(ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, "image/png")
                    .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes.toByteArray()))).build());
        }).build();
        var lookup = new OfficialMarathonResultLookup("", JsonMapper.builder().build(), unexpectedModel(),
                ObservationRegistry.NOOP, CLOCK, client, imageNotice);

        var result = lookup.lookup(SERIES, event(), 2025, Distance.MARATHON, deadline());

        assertThat(fetched).containsExactly(noticePage, attachment);
        if (!caseName.equals("verified")) {
            assertThat(result).isEmpty();
            return;
        }
        assertThat(result.orElseThrow().groups()).singleElement().satisfies(group -> {
            assertThat(group.category()).isEqualTo(Category.MEN);
            assertThat(group.subCategory()).isEqualTo(SubCategory.DOMESTIC);
            assertThat(group.source().url()).isEqualTo(noticePage.toString());
            assertThat(group.source().imageUrl()).isEqualTo(attachment.toString());
            assertThat(group.results()).containsExactly(new Rank(1, "张勇", null, "02:12:35", null),
                    new Rank(2, "王东", null, "02:13:48", null), new Rank(3, "李成", null, "02:14:55", null));
        });
    }

    private static OfficialMarathonImageNotice.Word word(int x, int y, String text) {
        return new OfficialMarathonImageNotice.Word(x, y, 20, 95, text);
    }

    private static OfficialMarathonResultLookup lookup(List<URI> seen, Map<String, String> pages,
                                                       List<String> candidates, ChatModel model) {
        var json = JsonMapper.builder().build();
        var client = WebClient.builder().exchangeFunction(request -> {
            URI url = request.url();
            seen.add(url);
            if (SEARCH.equals(url.toString())) {
                assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer synthetic-test-key");
                var results = candidates.stream().map(value -> Map.of("url", value, "title", "sample results",
                        "content", "Alice the snippet won the marathon in 2:11:11")).toList();
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(json.writeValueAsString(Map.of("results", results))).build());
            }
            assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
            var body = pages.get(url.toString());
            return Mono.just(body == null
                    ? ClientResponse.create(HttpStatus.FOUND).header(HttpHeaders.LOCATION, SOURCE + "/redirect").build()
                    : ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                            .body(body).build());
        }).build();
        return new OfficialMarathonResultLookup("synthetic-test-key", json, model, ObservationRegistry.NOOP, CLOCK, client);
    }

    private static Event event() {
        return event(EventStatus.CONFIRMED);
    }

    private static Event event(EventStatus status) {
        return new Event("sample_2025", "sample", 2025, RACE_DATE, "UTC", "Sample", null, "Test",
                List.of(Distance.MARATHON), Map.of(), null, null, status,
                new Source(SOURCE, "Sample Organizer", CLOCK.instant()));
    }

    private static String page(String title, String body) {
        return "<html><body><main><h1>" + title + "</h1>" + body + "</main></body></html>";
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    }

    private static ChatModel unexpectedModel() {
        return prompt -> { throw new AssertionError("No verified prose requiring extraction"); };
    }
}
