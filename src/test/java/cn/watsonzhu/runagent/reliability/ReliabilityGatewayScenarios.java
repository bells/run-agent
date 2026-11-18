package cn.watsonzhu.runagent.reliability;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import cn.watsonzhu.runagent.performance.*;
import cn.watsonzhu.runagent.multiagent.MultiAgentExecutionControl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.openai.*;
import org.springframework.ai.chat.prompt.Prompt;
import static org.assertj.core.api.Assertions.*;

/** Local synthetic HTTP gateway: no real provider, credentials or private data. */
public final class ReliabilityGatewayScenarios {
    public record Result(int status, boolean enabled, int httpRequests, int modelCalls, int unknownCalls,
            String costStatus, long latencyMs, int retries) { }
    public static Result verify(int failureStatus, boolean enabled) throws Exception {
        var requests=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            int count=requests.incrementAndGet();exchange.getRequestBody().readAllBytes();
            int status=count==1?failureStatus:200;
            String response=status==200
                ? "{\"id\":\"synthetic\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"synthetic\",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12},\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"synthetic safe answer\"},\"finish_reason\":\"stop\"}]}"
                : "{\"error\":{\"message\":\"PRIVATE_PROVIDER_BODY_SENTINEL\",\"type\":\"synthetic_error\"}}";
            byte[] body=response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });
        server.start();
        long started=System.nanoTime();
        try {
            var model=OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                .baseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1").apiKey("synthetic-key")
                .model("synthetic").maxRetries(0).build()).build();
            var properties=new ReliabilityProperties(enabled,new ReliabilityProperties.Retry(2,Duration.ofMillis(1),2,Duration.ofMillis(2),0),null,null,null);
            var meters=new SimpleMeterRegistry();
            var executor=new ReliabilityExecutor(properties,meters);
            var budget=new ExecutionBudget("synthetic-execution",new MultiAgentExecutionControl(Duration.ofSeconds(10)),PerformanceTestSupport.properties());
            boolean success=enabled && (failureStatus==429 || failureStatus==503 || failureStatus==200);
            try(var scope=ExecutionScope.open(budget)) {
                var reliable=new ReliableChatModel(model,ModelStage.KNOWLEDGE_COACH,ObservationRegistry.NOOP,executor);
                if(success || failureStatus==200)assertThat(reliable.call(new Prompt("synthetic input")).getResult().getOutput().getText()).isEqualTo("synthetic safe answer");
                else assertThatThrownBy(()->reliable.call(new Prompt("synthetic input"))).isInstanceOf(RuntimeException.class);
            }
            int expected=enabled && failureStatus!=200 && (failureStatus==429 || failureStatus==503)?2:1;
            assertThat(requests.get()).isEqualTo(expected);assertThat(budget.modelCalls()).isEqualTo(expected);
            var snapshot=PerformanceSnapshot.capture(budget,new CostEstimator(budget.properties().pricing()));
            assertThat(snapshot.unknownCalls()).isEqualTo(failureStatus==200?0:1);
            assertThat(snapshot.estimatedCost().status().name()).isEqualTo(failureStatus==200?"KNOWN":"UNKNOWN");
            assertThat(meters.getMeters()).allSatisfy(m->assertThat(m.getId().toString()).doesNotContain("PRIVATE_PROVIDER_BODY_SENTINEL","synthetic-execution","synthetic input"));
            int retries=(int)meters.find("runagent.reliability.retry").counters().stream().mapToDouble(c->c.count()).sum();
            assertThat(retries).isEqualTo(expected-1);
            return new Result(failureStatus,enabled,requests.get(),budget.modelCalls(),snapshot.unknownCalls(),snapshot.estimatedCost().status().name(),
                Duration.ofNanos(System.nanoTime()-started).toMillis(),retries);
        } finally { server.stop(0); }
    }
}
