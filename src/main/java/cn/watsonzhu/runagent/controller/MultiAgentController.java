package cn.watsonzhu.runagent.controller;

import java.util.concurrent.TimeoutException;
import cn.watsonzhu.runagent.config.MultiAgentProperties;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.model.MultiAgentRequest;
import cn.watsonzhu.runagent.model.MultiAgentResponse;
import cn.watsonzhu.runagent.multiagent.MultiAgentCoordinator;
import cn.watsonzhu.runagent.multiagent.MultiAgentExecutionControl;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/multi-agent/training-advice")
public class MultiAgentController {
    private final MultiAgentCoordinator coordinator;
    private final MultiAgentProperties properties;
    public MultiAgentController(MultiAgentCoordinator coordinator, MultiAgentProperties properties) {
        this.coordinator = coordinator;
        this.properties = properties;
    }
    @PostMapping
    public Mono<MultiAgentResponse> advise(@Valid @RequestBody MultiAgentRequest request) {
        return Mono.defer(() -> {
            var control = new MultiAgentExecutionControl(properties.timeout());
            return Mono.fromCallable(() -> {
                        try {
                            return coordinator.execute(request, control);
                        } catch (RuntimeException exception) {
                            // A cancelled subscription has no error consumer. Keep late failures out of Reactor's dropped-error path.
                            if (control.isCancelled()) return null;
                            throw exception;
                        }
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnCancel(control::cancel)
                    .timeout(properties.timeout())
                    .onErrorMap(TimeoutException.class, exception -> new MultiAgentException(MultiAgentException.Kind.TIMEOUT));
        });
    }
}
