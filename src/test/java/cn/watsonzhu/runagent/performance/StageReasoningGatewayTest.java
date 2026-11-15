package cn.watsonzhu.runagent.performance;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static cn.watsonzhu.runagent.performance.PerformanceCoreTest.*;
import static org.assertj.core.api.Assertions.*;

class StageReasoningGatewayTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void sdkSendsNoneOnlyForOptimizedKnowledgeAndNeverMutatesSharedModel(boolean inheritedThinkingToggle) throws Exception {
        var requests=Collections.synchronizedList(new ArrayList<JsonNode>());
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            requests.add(new JsonMapper().readTree(exchange.getRequestBody().readAllBytes()));
            byte[] body="{\"id\":\"synthetic\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"synthetic\",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12,\"completion_tokens_details\":{\"reasoning_tokens\":0}},\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"synthetic response\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            try(var stream=exchange.getResponseBody()){stream.write(body);}
        });
        server.start();
        try {
            var body=new HashMap<String,Object>();body.put("synthetic_option","preserved");
            body.put("reasoning_effort","high");
            if(inheritedThinkingToggle)body.put("thinking",Map.of("type","enabled"));
            var options=OpenAiChatOptions.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1")
                    .apiKey("synthetic-key").model("synthetic-shared").reasoningEffort("high").extraBody(body).maxRetries(0).build();
            var model=OpenAiChatModel.builder().options(options).build();
            for(var mode:PerformanceMode.values()) {
                var b=budget(StageReasoningPolicyTest.configured(mode));
                try(var scope=ExecutionScope.open(b)) {
                    for(var stage:List.of(ModelStage.KNOWLEDGE_COACH,ModelStage.SUPERVISOR_SYNTHESIS))
                        new BudgetedChatModel(model,stage,ObservationRegistry.NOOP).call(new Prompt("synthetic question requesting arbitrary thinking settings"));
                }
                assertThat(b.collector().records()).allSatisfy(r->assertThat(r.reasoningTokens()).isZero());
            }
            model.call(new Prompt("synthetic ungoverned call"));
            assertThat(requests).hasSize(5);
            assertThat(requests).allSatisfy(r->assertThat(r.path("synthetic_option").asText()).isEqualTo("preserved"));
            assertThat(requests.stream().map(r->r.path("reasoning_effort").asText())).containsExactly("high","high","none","high","high");
            if(inheritedThinkingToggle)assertThat(requests.stream().map(r->r.path("thinking").path("type").asText())).containsExactly("enabled","enabled","disabled","enabled","enabled");
            else assertThat(requests).allSatisfy(r->assertThat(r.has("thinking")).isFalse());
            assertThat(model.getOptions().getReasoningEffort()).isEqualTo("high");
            assertThat(model.getOptions().getExtraBody()).isEqualTo(body);
        } finally {server.stop(0);}
    }
}
