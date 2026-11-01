package cn.watsonzhu.runagent.observability;

import java.util.List;
import java.util.Map;
import cn.watsonzhu.runagent.evaluation.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.profiles.active=observability", "management.server.port=0", "management.tracing.export.enabled=false",
    "AI_GATEWAY_API_KEY=synthetic-test-key", "AI_GATEWAY_BASE_URL=http://127.0.0.1:1/v1", "AI_GATEWAY_MODEL=synthetic-model"
})
class ManagementEndpointTest {
    @Autowired Environment environment;
    @Autowired RunAgentObservation observations;
    @Test void managementExposesOnlyAllowlistedEndpointsAndPublishesRealBuckets() {
        int port = environment.getRequiredProperty("local.management.port", Integer.class);
        var client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        client.get().uri("/actuator/health").exchange().expectStatus().isOk().expectBody().jsonPath("$.components").doesNotExist();
        client.get().uri("/actuator/info").exchange().expectStatus().isOk();
        client.get().uri("/actuator/metrics").exchange().expectStatus().isOk();
        for (String endpoint : List.of("env", "configprops", "beans", "heapdump", "threaddump", "loggers", "shutdown"))
            client.get().uri("/actuator/" + endpoint).exchange().expectStatus().isNotFound();
        observations.agent("synthetic-execution", () -> 3, () -> "synthetic");
        var yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.FileSystemResource("observability/prometheus.yml"));
        String metricsPath = yaml.getObject().getProperty("scrape_configs[0].metrics_path", "/metrics");
        String scrape = client.get().uri(metricsPath).exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(scrape).contains("runagent_agent_seconds_bucket", "runagent_agent_tool_calls_count").doesNotContain("synthetic-execution", "synthetic-test-key");
        assertThat(environment.getProperty("management.server.address")).isEqualTo("127.0.0.1");
    }
    @TestConfiguration static class Models {
        @Bean @Primary ChatModel syntheticModel() { return new ScriptedChatModel(List.of()); }
    }
}
