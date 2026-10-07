package cn.watsonzhu.runagent.controller;

import cn.watsonzhu.runagent.config.RagProperties;
import cn.watsonzhu.runagent.exception.KnowledgeBaseUnavailableException;
import cn.watsonzhu.runagent.model.KnowledgeSearchRequest;
import cn.watsonzhu.runagent.model.KnowledgeSearchResponse;
import cn.watsonzhu.runagent.rag.KnowledgeSearchService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.concurrent.TimeoutException;

@RestController
@Profile("local")
@RequestMapping("/api/knowledge")
public class KnowledgeSearchController {
    private final KnowledgeSearchService service;
    private final RagProperties properties;
    public KnowledgeSearchController(KnowledgeSearchService service, RagProperties properties) {
        this.service = service;
        this.properties = properties;
    }
    @PostMapping("/search")
    public Mono<KnowledgeSearchResponse> search(@Valid @RequestBody KnowledgeSearchRequest request) {
        return Mono.fromCallable(() -> service.search(request.query())).subscribeOn(Schedulers.boundedElastic())
                .timeout(properties.timeout()).onErrorMap(TimeoutException.class,
                        e -> new KnowledgeBaseUnavailableException("Knowledge request timed out."));
    }
}
