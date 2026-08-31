package cn.watsonzhu.runagent.controller;

import cn.watsonzhu.runagent.agent.RunAgentService;
import cn.watsonzhu.runagent.model.ChatRequest;
import cn.watsonzhu.runagent.model.ChatResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Validated
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final RunAgentService runAgentService;

    public ChatController(RunAgentService runAgentService) {
        this.runAgentService = runAgentService;
    }

    @PostMapping
    public Mono<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("Chat request received, messageLength={}", request.message().length());
        return Mono.fromCallable(() -> new ChatResponse(runAgentService.chat(request.message())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<ServerSentEvent<String>> stream(
            @RequestParam
            @NotBlank(message = "message must not be blank")
            @Size(max = 4_000, message = "message must not exceed 4000 characters")
            String message) {
        log.info("Streaming chat request received, messageLength={}", message.length());
        return runAgentService.stream(message)
                .map(content -> ServerSentEvent.<String>builder()
                        .event("token")
                        .data(content)
                        .build())
                .onErrorResume(exception -> {
                    log.warn("Streaming response ended with an error: {}", exception.getMessage());
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("The AI service is temporarily unavailable")
                            .build());
                });
    }
}
