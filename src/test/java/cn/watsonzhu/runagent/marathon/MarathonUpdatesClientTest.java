package cn.watsonzhu.runagent.marathon;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static cn.watsonzhu.runagent.marathon.MarathonUpdates.*;
import static org.assertj.core.api.Assertions.*;

class MarathonUpdatesClientTest {
    private static final Query QUERY = new Query(Kind.REGISTRATION, "上海马拉松", 2026,
            null, null, null, List.of(), List.of("race.example.org"));

    @Test
    void onlySendsBoundedSearchEvidenceToTheExtractorAndRetainsServiceUrls() {
        var requests = new AtomicInteger();
        WebClient http = WebClient.builder().exchangeFunction(request -> {
            requests.incrementAndGet();
            assertThat(request.url()).isEqualTo(URI.create("https://api.tavily.com/search"));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"results":[
                              {"title":"拒绝危险链接","url":"https://trusted.example@private.example/","content":"不可信文本"},
                              {"title":"2026上海马拉松","url":"https://race.example.org/brief","content":"全马参赛规模为30,000人","published_date":"2026-11-27"},
                              {"title":"重复结果","url":"https://race.example.org/brief","content":"重复文本"}
                            ]}
                            """).build());
        }).build();
        ChatModel model = prompt -> {
            assertThat(prompt.getUserMessage().getText())
                    .contains("上海马拉松 2026", "全马参赛规模为30,000人")
                    .doesNotContain("不可信文本", "重复文本", "trusted.example");
            return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                    {"findings":[{"source":0,"fact":"CAPACITY","value":"30,000","quote":"全马参赛规模为30,000人"}]}
                    """))));
        };

        var lookup = new MarathonUpdatesClient("test-key", model, ObservationRegistry.NOOP, http).lookup(QUERY);

        assertThat(requests).hasValue(1);
        assertThat(lookup.sources()).singleElement().satisfies(source -> {
            assertThat(source.url()).isEqualTo(URI.create("https://race.example.org/brief"));
            assertThat(source.publishedAt()).hasToString("2026-11-27");
        });
        assertThat(lookup.findings()).singleElement().extracting(Finding::value).isEqualTo("30,000");
    }

    @Test
    void providerErrorDoesNotBecomeAnEmptySearch() {
        WebClient http = WebClient.builder().exchangeFunction(request ->
                Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).build())).build();
        ChatModel unexpected = prompt -> { throw new AssertionError("search failed"); };
        var client = new MarathonUpdatesClient("test-key", unexpected, ObservationRegistry.NOOP, http);
        assertThatThrownBy(() -> client.lookup(QUERY)).isInstanceOf(IllegalStateException.class);
    }
    @Test
    void cancelsAStalledProviderInsteadOfWaitingForModelExtraction() {
        var cancelled = new AtomicBoolean();
        WebClient http = WebClient.builder().exchangeFunction(request ->
                Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true))).build();
        ChatModel unexpected = prompt -> { throw new AssertionError("search never responded"); };
        var client = new MarathonUpdatesClient("test-key", unexpected, ObservationRegistry.NOOP, http);

        assertThatThrownBy(() -> client.lookup(QUERY)).isInstanceOf(IllegalStateException.class);
        assertThat(cancelled).isTrue();
    }

}
