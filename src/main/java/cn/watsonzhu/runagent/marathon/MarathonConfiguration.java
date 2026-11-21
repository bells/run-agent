package cn.watsonzhu.runagent.marathon;

import java.nio.file.Path;
import java.time.Clock;

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
                        .warn("marathon_podium_lookup status=DISABLED_MISSING_CONFIGURATION");
            } else {
                lookup = new OfficialMarathonResultLookup(tavilyApiKey.strip(), json, model, observations, clock);
            }
        }
        return new MarathonService(calendar, new MarathonPodiumStore(json, podiumDirectory), lookup, clock);
    }

    @Bean
    MarathonChat marathonChat(MarathonService service) {
        return new MarathonChat(service, Clock.systemUTC());
    }

    @Bean
    MarathonTools marathonTools(MarathonService service) {
        return new MarathonTools(service);
    }
}
