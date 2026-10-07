package cn.watsonzhu.runagent.controller;

import cn.watsonzhu.runagent.config.RagProperties;
import cn.watsonzhu.runagent.exception.KnowledgeBaseUnavailableException;
import cn.watsonzhu.runagent.model.KnowledgeQuestionRequest;
import cn.watsonzhu.runagent.model.KnowledgeAnswerResponse;
import cn.watsonzhu.runagent.rag.KnowledgeQaService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeQaService service;
    private final RagProperties properties;
    public KnowledgeController(KnowledgeQaService service, RagProperties properties) {
        this.service = service;
        this.properties = properties;
    }
    @PostMapping("/ask")
    public Mono<KnowledgeAnswerResponse> ask(@Valid @RequestBody KnowledgeQuestionRequest request) {
        return Mono.fromCallable(() -> service.ask(request.question())).subscribeOn(Schedulers.boundedElastic())
                .timeout(properties.timeout()).onErrorMap(TimeoutException.class,
                        e -> new KnowledgeBaseUnavailableException("Knowledge request timed out."));
    }
}
