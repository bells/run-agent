package cn.watsonzhu.runagent.performance;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static cn.watsonzhu.runagent.performance.PerformanceCoreTest.*;
import static org.assertj.core.api.Assertions.*;

class PerformanceGatewayTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=ModelStage.class,names={"KNOWLEDGE_COACH","SUPERVISOR_SYNTHESIS"})
    void sdkLengthFinishReasonCannotPublishTruncatedEvidenceOrFinalAnswer(ModelStage stage) throws Exception {
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body="{\"id\":\"synthetic\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"synthetic\",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":1500,\"total_tokens\":1510},\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"synthetic unfinished table\"},\"finish_reason\":\"length\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);
            try(var stream=exchange.getResponseBody()){stream.write(body);}
        });
        server.start();
        try {
            var delegate=OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                    .baseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1").apiKey("synthetic-key")
                    .model("synthetic").maxRetries(0).build()).build();
            var b=budget(properties(PerformanceMode.BASELINE,16,30000));
            try(var scope=ExecutionScope.open(b)) {
                assertThatThrownBy(()->new BudgetedChatModel(delegate,stage,ObservationRegistry.NOOP).call(new Prompt("synthetic input")))
                        .isInstanceOf(cn.watsonzhu.runagent.exception.MultiAgentException.class);
            }
            assertThat(requests.get()).isOne();
            assertThat(b.reasons()).contains(BudgetReason.OUTPUT_TOKENS);
            assertThat(b.collector().records()).singleElement().satisfies(r->{
                assertThat(r.status()).isEqualTo(ModelCallStatus.FAILED);
                assertThat(r.totalTokens()).isEqualTo(1510);
            });
        } finally {server.stop(0);}
    }
    @Test void springAi201SendsPerStageConfiguredModelAndMaxTokensAcrossSameGateway() throws Exception {
        var requests=Collections.synchronizedList(new ArrayList<JsonNode>());
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            requests.add(new JsonMapper().readTree(exchange.getRequestBody().readAllBytes()));
            byte[] response="{\"id\":\"synthetic\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"synthetic\",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12,\"completion_tokens_details\":{\"reasoning_tokens\":1}},\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"synthetic response\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);
            try(var stream=exchange.getResponseBody()){stream.write(response);}
        });
        server.start();
        try {
            var delegate=OpenAiChatModel.builder().options(OpenAiChatOptions.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1")
                    .apiKey("synthetic-key").model("default-must-be-overridden").maxRetries(0).build()).build();
            var b=budget(properties(PerformanceMode.OPTIMIZED,16,30000));
            try(var scope=ExecutionScope.open(b)) {
                for(var stage:ModelStage.values())new BudgetedChatModel(delegate,stage,ObservationRegistry.NOOP).call(new Prompt("synthetic input"));
            }
            assertThat(requests).hasSize(5);
            for(int i=0;i<ModelStage.values().length;i++) {
                var route=new ModelRoutePolicy(b.properties()).resolve(ModelStage.values()[i]);
                assertThat(requests.get(i).path("model").asText()).isEqualTo(route.model());
                assertThat(requests.get(i).path("max_tokens").asInt()).isEqualTo(route.maxOutputTokens());
            }
            assertThat(b.collector().knownTotalTokens()).isEqualTo(60);
            assertThat(b.collector().records()).allSatisfy(record -> {
                assertThat(record.reasoningTokens()).isEqualTo(1);
                assertThat(record.finishReason()).isEqualTo(ModelUsageRecord.FinishReason.STOP);
                assertThat(record.outputCharacters()).isPositive();
            });
        } finally {server.stop(0);}
    }
}
