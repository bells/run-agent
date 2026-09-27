package cn.watsonzhu.runagent.controller;

import java.util.concurrent.TimeoutException;

import cn.watsonzhu.runagent.agent.RunAgentService;
import cn.watsonzhu.runagent.config.AgentProperties;
import cn.watsonzhu.runagent.exception.AgentTimeoutException;
import cn.watsonzhu.runagent.model.AgentResponse;
import cn.watsonzhu.runagent.model.AgentRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final RunAgentService service;
    private final AgentProperties properties;

    public AgentController(RunAgentService service, AgentProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping
    public Mono<AgentResponse> agent(@Valid @RequestBody AgentRequest request) {
        return Mono.fromCallable(() -> service.agent(request.conversationId(), request.message()))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(properties.timeout())
                .onErrorMap(TimeoutException.class, exception -> new AgentTimeoutException());
    }
}
