package cn.watsonzhu.runagent.controller;

import cn.watsonzhu.runagent.agent.RunAgentService;
import cn.watsonzhu.runagent.model.ChatRequest;
import cn.watsonzhu.runagent.model.RunningIntent;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/intent")
public class IntentController {

    private static final Logger log = LoggerFactory.getLogger(IntentController.class);

    private final RunAgentService runAgentService;

    public IntentController(RunAgentService runAgentService) {
        this.runAgentService = runAgentService;
    }

    @PostMapping
    public Mono<RunningIntent> parseIntent(@Valid @RequestBody ChatRequest request) {
        log.info("Intent request received, messageLength={}", request.message().length());
        // Structured Output 同样会同步等待模型响应，故移到适合阻塞任务的线程池。
        return Mono.fromCallable(() -> runAgentService.parseRunningIntent(request.message()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
