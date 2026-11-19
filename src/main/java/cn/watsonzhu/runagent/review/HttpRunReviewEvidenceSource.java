package cn.watsonzhu.runagent.review;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
@ConditionalOnProperty(prefix = "run-agent.run-review", name = {"enabled", "generation-enabled"}, havingValue = "true")
final class HttpRunReviewEvidenceSource implements RunReviewEvidenceSource {
    private static final int MAX_BYTES = 32 * 1024;
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(3);
    private static final Set<String> EVIDENCE_FIELDS = Set.of(
            "schemaVersion", "runId", "distanceMeters", "movingSeconds", "splits");
    private static final Set<String> SPLIT_FIELDS = Set.of("distanceMeters", "movingSeconds", "partial");

    private final String evidencePrefix;
    private final ObjectReader reader;
    private final WebClient client;

    @Autowired
    HttpRunReviewEvidenceSource(@Value("${run-agent.run-review.evidence-base-url:}") String baseUrl, ObjectMapper json) {
        this(baseUrl, json, WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().followRedirect(false)))
                .build());
    }

    HttpRunReviewEvidenceSource(String baseUrl, ObjectMapper json, WebClient client) {
        this.evidencePrefix = prefix(baseUrl);
        this.reader = json.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.client = client;
    }

    @Override
    public RunReviewEvidence load(long runId) {
        if (!RunReviewEvidence.validRunId(runId)) {
            throw new RunReviewException(RunReviewException.Code.RUN_NOT_FOUND);
        }
        try {
            // The activity ID is the only request input involved in building this URI.
            return client.get().uri(URI.create(evidencePrefix + runId + ".json"))
                    .accept(MediaType.APPLICATION_JSON)
                    .exchangeToMono(response -> decode(response, runId))
                    .timeout(FETCH_TIMEOUT)
                    .switchIfEmpty(Mono.error(HttpRunReviewEvidenceSource::unavailable))
                    .block();
        } catch (RunReviewException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private Mono<RunReviewEvidence> decode(ClientResponse response, long expectedRunId) {
        if (response.statusCode() == HttpStatus.NOT_FOUND) {
            return Mono.error(new RunReviewException(RunReviewException.Code.RUN_NOT_FOUND));
        }
        if (response.statusCode() != HttpStatus.OK
                || response.headers().contentLength().orElse(0) > MAX_BYTES
                || response.headers().contentType().filter(MediaType.APPLICATION_JSON::isCompatibleWith).isEmpty()) {
            return Mono.error(unavailable());
        }
        return DataBufferUtils.join(response.body(BodyExtractors.toDataBuffers()), MAX_BYTES).map(buffer -> {
            try {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                return evidence(reader.readTree(bytes), expectedRunId);
            } finally {
                DataBufferUtils.release(buffer);
            }
        });
    }

    private static RunReviewEvidence evidence(JsonNode node, long expectedRunId) {
        checkFields(node, EVIDENCE_FIELDS);
        long schemaVersion = positiveInteger(node.get("schemaVersion"));
        long id = positiveInteger(node.get("runId"));
        if (schemaVersion != 1 || id != expectedRunId) {
            throw unavailable();
        }
        JsonNode splitsNode = node.get("splits");
        if (!splitsNode.isArray() || splitsNode.size() > RunReviewEvidence.MAX_SPLITS) {
            throw unavailable();
        }
        List<RunReviewEvidence.Split> splits = new ArrayList<>(splitsNode.size());
        for (JsonNode split : splitsNode) {
            checkFields(split, SPLIT_FIELDS);
            JsonNode partial = split.get("partial");
            if (!partial.isBoolean()) {
                throw unavailable();
            }
            splits.add(new RunReviewEvidence.Split(distance(split.get("distanceMeters")),
                    positiveInteger(split.get("movingSeconds")), partial.booleanValue()));
        }
        return new RunReviewEvidence((int) schemaVersion, id, distance(node.get("distanceMeters")),
                positiveInteger(node.get("movingSeconds")), splits);
    }

    private static void checkFields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()
                || !node.propertyNames().containsAll(fields)) {
            throw unavailable();
        }
    }

    private static double distance(JsonNode value) {
        if (!value.isNumber() || !RunReviewEvidence.positiveDistance(value.doubleValue())) {
            throw unavailable();
        }
        return value.doubleValue();
    }

    private static long positiveInteger(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong()
                || !RunReviewEvidence.positiveSeconds(value.longValue())) {
            throw unavailable();
        }
        return value.longValue();
    }

    private static String prefix(String baseUrl) {
        URI base = URI.create(baseUrl);
        String host = base.getHost();
        String path = base.getRawPath();
        if (!"https".equalsIgnoreCase(base.getScheme()) || host == null
                || !host.matches("[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+")
                || host.matches("[0-9.]+") || host.toLowerCase(Locale.ROOT).endsWith(".localhost")
                || base.getRawUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null
                || !base.normalize().equals(base) || !path.matches("(?:/[a-zA-Z0-9._~-]+)*/?")) {
            throw new IllegalArgumentException("The run review evidence source requires a fixed HTTPS site URL");
        }
        return base.toASCIIString().replaceFirst("/$", "") + "/run-review-evidence/";
    }

    private static RunReviewException unavailable() {
        return new RunReviewException(RunReviewException.Code.EVIDENCE_UNAVAILABLE);
    }
}
