package cn.watsonzhu.runagent.evidence;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import cn.watsonzhu.runagent.rag.KnowledgeSearchService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Service
public class RunningEvidenceService {
    private static final int MAX_EXCERPTS = 3;
    private static final int MAX_EXCERPT_CHARS = 650;
    private final KnowledgeSearchService knowledge;
    private final WeatherEvidenceService forecasts;
    private final Scheduler io = Schedulers.newBoundedElastic(4, 8, "running-evidence");

    public RunningEvidenceService(KnowledgeSearchService knowledge, WeatherEvidenceService forecasts) {
        this.knowledge = knowledge;
        this.forecasts = forecasts;
    }

    public Bundle collect(String topic, AdviceContext context) {
        var bookEvidence = Mono.fromCallable(() -> retrieve(topic))
                .subscribeOn(io).timeout(Duration.ofSeconds(3))
                .onErrorReturn(Books.withStatus(EvidenceStatus.UNAVAILABLE));
        return Mono.zip(bookEvidence, forecast(context), (books, weather) -> {
            String contextText = "Knowledge status: " + books.evidence().status()
                    + "\n" + books.promptContext()
                    + "\nWeather status: " + weather.status()
                    + (weather.status() == EvidenceStatus.READY ? "\n" + weather : "");
            return new Bundle(new Evidence(books.evidence(), weather), contextText, books.excerpts());
        }).block();
    }

    public Weather weather(AdviceContext context) {
        return forecast(context).block();
    }

    private Mono<Weather> forecast(AdviceContext context) {
        if (context.city() == null) {
            return Mono.just(Weather.status(EvidenceStatus.NOT_REQUESTED));
        }
        return Mono.fromCallable(() -> forecasts.read(context))
                .subscribeOn(io).timeout(Duration.ofSeconds(5))
                .onErrorReturn(Weather.status(EvidenceStatus.UNAVAILABLE));
    }

    private Books retrieve(String topic) {
        if (topic == null || topic.isBlank()) {
            return Books.withStatus(EvidenceStatus.NOT_REQUESTED);
        }
        if (!knowledge.isAvailable()) {
            return Books.withStatus(EvidenceStatus.UNAVAILABLE);
        }
        var matches = knowledge.retrieveDocuments(UUID.randomUUID().toString(), truncate(topic, 600));
        List<Source> sources = new ArrayList<>();
        StringBuilder promptContext = new StringBuilder();
        List<String> excerpts = new ArrayList<>();
        for (var match : matches) {
            if (excerpts.size() == MAX_EXCERPTS) break;
            Object rawTitle = match.getMetadata().get("sourceTitle");
            String text = match.getText();
            if (!(rawTitle instanceof String title) || title.isBlank() || text == null || text.isBlank()) continue;
            Object rawChapter = match.getMetadata().get("chapter");
            var source = new Source(truncate(title, 160),
                    rawChapter instanceof String chapter ? truncate(chapter, 160) : null);
            if (!sources.contains(source)) sources.add(source);
            String excerpt = truncate(text, MAX_EXCERPT_CHARS);
            excerpts.add(excerpt);
            promptContext.append("Retrieved from ").append(source.title()).append(":\n")
                    .append(excerpt).append("\n\n");
        }
        EvidenceStatus status = sources.isEmpty() ? EvidenceStatus.NO_MATCH : EvidenceStatus.READY;
        return new Books(new Knowledge(status, List.copyOf(sources)), promptContext.toString(), List.copyOf(excerpts));
    }

    private static String truncate(String text, int maxCodePoints) {
        int size = text.codePointCount(0, text.length());
        return text.substring(0, text.offsetByCodePoints(0, Math.min(size, maxCodePoints)));
    }

    @PreDestroy
    void close() {
        io.dispose();
    }

    public enum EvidenceStatus { READY, NOT_REQUESTED, UNAVAILABLE, NO_MATCH }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(String title, String chapter) { }
    public record Knowledge(EvidenceStatus status, List<Source> sources) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Weather(EvidenceStatus status, String city, String timezone, String validAt,
                          String observedAt, Double temperatureC, Double apparentTemperatureC,
                          Integer precipitationProbability, Double windKph, Integer europeanAqi) {
        static Weather status(EvidenceStatus status) {
            return new Weather(status, null, null, null, null, null, null, null, null, null);
        }
    }

    public record Evidence(Knowledge knowledge, Weather weather) { }
    private record Books(Knowledge evidence, String promptContext, List<String> excerpts) {
        static Books withStatus(EvidenceStatus status) {
            return new Books(new Knowledge(status, List.of()), "", List.of());
        }
    }
    public record Bundle(Evidence evidence, String promptContext, List<String> excerpts) {
        private static final int VERBATIM_LIMIT = 96;
        private static final Pattern FORMATTING = Pattern.compile("[\\p{Z}\\p{P}\\p{S}\\s]+");

        public Bundle(Evidence evidence, String promptContext) {
            this(evidence, promptContext, List.of());
        }

        public boolean allowsAnswer(String answer) {
            if (excerpts.isEmpty()) return true;
            String normalizedAnswer = FORMATTING.matcher(answer).replaceAll("");
            if (normalizedAnswer.length() < VERBATIM_LIMIT) return true;
            for (String excerpt : excerpts) {
                String text = FORMATTING.matcher(excerpt).replaceAll("");
                for (int start = 0; start <= text.length() - VERBATIM_LIMIT; start++) {
                    if (normalizedAnswer.contains(text.substring(start, start + VERBATIM_LIMIT))) return false;
                }
            }
            return true;
        }

        public static Bundle empty() {
            return new Bundle(new Evidence(new Knowledge(EvidenceStatus.NOT_REQUESTED, List.of()),
                    Weather.status(EvidenceStatus.NOT_REQUESTED)), "");
        }
    }
}
