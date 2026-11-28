package cn.watsonzhu.runagent.marathon;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "run-agent.marathon", name = "enabled", havingValue = "true")
class MarathonConfiguration {
    @Bean
    MarathonService marathonService(ObjectMapper json, ChatModel model, ObservationRegistry observations,
            @Value("${run-agent.marathon.snapshot:}") String snapshot,
            @Value("${run-agent.marathon.podium-directory}") Path podiumDirectory,
            @Value("${run-agent.marathon.lookup-enabled:false}") boolean lookupEnabled,
            @Value("${run-agent.marathon.tavily-api-key:}") String tavilyApiKey) {
        Clock clock = Clock.systemUTC();
        var calendar = new MarathonCalendar(json, snapshot.isBlank() ? null : Path.of(snapshot), clock);
        MarathonResultLookup lookup = null;
        if (lookupEnabled) {
            if (tavilyApiKey.isBlank()) {
                LoggerFactory.getLogger(MarathonConfiguration.class)
                        .warn("marathon_podium_lookup status=SEARCH_DISABLED_MISSING_KEY");
            }
            lookup = new OfficialMarathonResultLookup(tavilyApiKey.strip(), json, model, observations, clock);
        }
        return new MarathonService(calendar, new MarathonPodiumStore(json, podiumDirectory), lookup, clock);
    }

    @Bean
    MarathonSub3 marathonSub3(ObjectMapper json, @Value("${run-agent.marathon.sub-three-snapshot:}") String path) {
        return new MarathonSub3(json, path.isBlank() ? null : Path.of(path), Clock.systemUTC());
    }

    @Bean
    MarathonEvidenceIndex marathonEvidenceIndex(ObjectMapper json,
            @Value("${run-agent.marathon.evidence-corpus:}") String corpus) {
        return new MarathonEvidenceIndex(json, corpus.isBlank() ? null : Path.of(corpus));
    }

    @Bean
    @ConditionalOnProperty(prefix = "run-agent.marathon", name = "updates-enabled", havingValue = "true")
    MarathonUpdates marathonUpdates(ChatModel model, ObservationRegistry observations,
            @Value("${run-agent.marathon.tavily-api-key:}") String apiKey,
            @Value("${run-agent.marathon.updates-visitor-daily-limit:12}") int visitorLimit,
            @Value("${run-agent.marathon.updates-owner-daily-limit:24}") int ownerLimit,
            @Value("${run-agent.marathon.updates-disable-thinking:false}") boolean disableThinking) {
        if (apiKey.isBlank()) {
            LoggerFactory.getLogger(MarathonConfiguration.class).warn("marathon_updates status=MISSING_SEARCH_KEY");
        }
        var client = new MarathonUpdatesClient(apiKey, model, observations, disableThinking);
        return new MarathonUpdates(Clock.systemUTC(), visitorLimit, ownerLimit, client::lookup);
    }

    @Bean
    MarathonChat marathonChat(MarathonService service, MarathonSub3 subThree, MarathonEvidenceIndex raceEvidence,
                              Optional<MarathonUpdates> updates) {
        return new MarathonChat(service, Clock.systemUTC(), subThree, raceEvidence, updates.orElse(null));
    }

    @Bean
    MarathonTools marathonTools(MarathonService service, MarathonSub3 subThree) {
        return new MarathonTools(service, subThree);
    }
}
