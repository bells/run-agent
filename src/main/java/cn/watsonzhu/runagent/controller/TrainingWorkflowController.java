package cn.watsonzhu.runagent.controller;

import java.util.concurrent.TimeoutException;
import cn.watsonzhu.runagent.config.WorkflowProperties;
import cn.watsonzhu.runagent.exception.WorkflowException;
import cn.watsonzhu.runagent.model.TrainingAnalysisRequest;
import cn.watsonzhu.runagent.model.TrainingAnalysisResponse;
import cn.watsonzhu.runagent.workflow.TrainingAnalysisWorkflow;
import cn.watsonzhu.runagent.workflow.WorkflowExecutionControl;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/workflows/training-analysis")
public class TrainingWorkflowController {
    private final TrainingAnalysisWorkflow workflow;
    private final WorkflowProperties properties;

    public TrainingWorkflowController(TrainingAnalysisWorkflow workflow, WorkflowProperties properties) {
        this.workflow = workflow;
        this.properties = properties;
    }

    @PostMapping
    public Mono<TrainingAnalysisResponse> analyze(@Valid @RequestBody TrainingAnalysisRequest request) {
        return Mono.defer(() -> {
            var control = new WorkflowExecutionControl(properties.timeout());
            return Mono.fromCallable(() -> workflow.execute(request, control))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnCancel(control::cancel)
                .timeout(properties.timeout())
                .onErrorMap(TimeoutException.class, exception -> new WorkflowException(WorkflowException.Kind.TIMEOUT));
        });
    }
}
