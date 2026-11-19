package cn.watsonzhu.runagent.review;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpRunReviewEvidenceSourceTest {
    private static final String RECORD = """
            {"schemaVersion":1,"runId":314,"distanceMeters":4000,"movingSeconds":1260,
             "splits":[{"distanceMeters":1000,"movingSeconds":300,"partial":false}]}
            """;
    private final AtomicReference<URI> requestedUri = new AtomicReference<>();

    @Test
    void buildsPathFromTheConfiguredHostAndBasePath() {
        var evidence = source(HttpStatus.OK, MediaType.APPLICATION_JSON_VALUE, RECORD).load(314);
        assertThat(requestedUri.get()).isEqualTo(URI.create("https://run.example/base/run-review-evidence/314.json"));
        assertThat(evidence.runId()).isEqualTo(314);
        assertThat(evidence.distanceMeters()).isEqualTo(4000);
        assertThat(evidence.splits()).containsExactly(new RunReviewEvidence.Split(1000, 300, false));
    }

    @Test
    void real404AndUnexpectedHtmlAreDifferentFailures() {
        assertFailure(HttpStatus.NOT_FOUND, MediaType.TEXT_HTML_VALUE, "404", RunReviewException.Code.RUN_NOT_FOUND);
        assertFailure(HttpStatus.OK, MediaType.TEXT_HTML_VALUE, "<html></html>", RunReviewException.Code.EVIDENCE_UNAVAILABLE);
    }

    @Test
    void redirectIsAnUpstreamFailure() {
        assertFailure(HttpStatus.FOUND, MediaType.APPLICATION_JSON_VALUE, RECORD, RunReviewException.Code.EVIDENCE_UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"schemaVersion\":1,\"runId\":315,\"distanceMeters\":1,\"movingSeconds\":1,\"splits\":[]}",
            "{\"schemaVersion\":1,\"runId\":314,\"distanceMeters\":1,\"movingSeconds\":\"1\",\"splits\":[]}",
            "{\"schemaVersion\":1,\"runId\":314,\"distanceMeters\":1,\"movingSeconds\":1,\"splits\":[],\"route\":[]}",
            "{\"schemaVersion\":1,\"runId\":314,\"runId\":314,\"distanceMeters\":1,\"movingSeconds\":1,\"splits\":[]}",
            "{\"schemaVersion\":1,\"runId\":314,\"distanceMeters\":1,\"movingSeconds\":1,\"splits\":[]} {}"
    })
    void rejectsBadIdentityShapeTypesAndDuplicateFields(String invalid) {
        assertFailure(HttpStatus.OK, MediaType.APPLICATION_JSON_VALUE, invalid, RunReviewException.Code.EVIDENCE_UNAVAILABLE);
    }

    @Test
    void enforcesBodySizeBeforeParsingEvenWithoutContentLength() {
        assertFailure(HttpStatus.OK, MediaType.APPLICATION_JSON_VALUE, RECORD + " ".repeat(32768),
                RunReviewException.Code.EVIDENCE_UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://run.example", "https://run.example/?source=other",
            "https://user@run.example", "https://run.example/base/../other", "https://run.example#anchor"})
    void rejectsUnsafeProductionBaseUris(String raw) {
        assertThatThrownBy(() -> new HttpRunReviewEvidenceSource(raw, new JsonMapper()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertFailure(HttpStatus status, String contentType, String body, RunReviewException.Code code) {
        assertThatThrownBy(() -> source(status, contentType, body).load(314))
                .isInstanceOfSatisfying(RunReviewException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private HttpRunReviewEvidenceSource source(HttpStatus status, String contentType, String result) {
        var client = WebClient.builder().exchangeFunction(request -> {
            requestedUri.set(request.url());
            return Mono.just(ClientResponse.create(status)
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.LOCATION, "https://other.example/")
                    .body(result).build());
        }).build();
        return new HttpRunReviewEvidenceSource("https://run.example/base/", new JsonMapper(), client);
    }
}
