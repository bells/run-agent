package cn.watsonzhu.runagent.workflow;

import java.util.Map;
import java.util.UUID;
import cn.watsonzhu.runagent.config.WorkflowProperties;
import cn.watsonzhu.runagent.exception.WorkflowException;
import cn.watsonzhu.runagent.model.TrainingAnalysisRequest;
import cn.watsonzhu.runagent.model.TrainingAnalysisResponse;
import cn.watsonzhu.runagent.workflow.node.*;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Service;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

@Service
public class TrainingAnalysisWorkflow {
    private final CompiledGraph<TrainingAnalysisState> graph;
    private final WorkflowProperties properties;

    public TrainingAnalysisWorkflow(ValidateInputNode validate, RunningDataNode running, KnowledgeRetrievalNode knowledge,
                                    AnalysisNode analysis, ValidateOutputNode output, WorkflowProperties properties) {
        this.properties = properties;
        try {
            this.graph = new StateGraph<>(TrainingAnalysisState.SCHEMA, TrainingAnalysisState::new)
                    .addNode("validateInput", traced("validateInput", validate))
                    .addNode("fetchRunningData", traced("fetchRunningData", running))
                    .addNode("retrieveKnowledge", traced("retrieveKnowledge", knowledge))
                    .addNode("analyze", traced("analyze", analysis))
                    .addNode("validateOutput", traced("validateOutput", output))
                    .addEdge(StateGraph.START, "validateInput")
                    .addConditionalEdges("validateInput", edge_async(state -> state.mode().name()), Map.of(
                            "RUNNING_ONLY", "fetchRunningData", "KNOWLEDGE_ONLY", "retrieveKnowledge", "COMBINED", "fetchRunningData"))
                    .addConditionalEdges("fetchRunningData", edge_async(state -> state.mode().name()), Map.of(
                            "RUNNING_ONLY", "analyze", "COMBINED", "retrieveKnowledge"))
                    .addEdge("retrieveKnowledge", "analyze")
                    .addEdge("analyze", "validateOutput")
                    .addEdge("validateOutput", StateGraph.END)
                    .compile();
        } catch (GraphStateException exception) {
            throw new IllegalStateException("Training workflow graph could not be compiled");
        }
    }

    public TrainingAnalysisResponse execute(TrainingAnalysisRequest request) {
        return execute(request, new WorkflowExecutionControl(properties.timeout()));
    }

    public TrainingAnalysisResponse execute(TrainingAnalysisRequest request, WorkflowExecutionControl control) {
        String executionId = UUID.randomUUID().toString();
        try {
            control.checkActive();
            var state = graph.invoke(TrainingAnalysisState.initial(request, executionId,
                    control.deadlineNanos()), RunnableConfig.builder().addMetadata(WorkflowExecutionControl.CONFIG_KEY, control).build())
                    .orElseThrow(() -> new WorkflowException(WorkflowException.Kind.EXECUTION_FAILED));
            return new TrainingAnalysisResponse(executionId, state.mode(), state.executedNodes(), state.analysis());
        } catch (RuntimeException exception) {
            // Async graph boundaries can wrap the domain exception. Never log the wrapper or its state.
            Throwable cause = exception;
            while (cause != null) {
                if (cause instanceof WorkflowException failure) throw failure;
                if (cause == cause.getCause()) break;
                cause = cause.getCause();
            }
            throw new WorkflowException(WorkflowException.Kind.EXECUTION_FAILED);
        }
    }

    private static AsyncNodeActionWithConfig<TrainingAnalysisState> traced(String name, NodeAction<TrainingAnalysisState> action) {
        return AsyncNodeActionWithConfig.node_async((state, config) -> WorkflowExecutionTrace.run(state,
                (WorkflowExecutionControl) config.metadata(WorkflowExecutionControl.CONFIG_KEY).orElseThrow(), name, () -> {
            try {
                return action.apply(state);
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new WorkflowException(WorkflowException.Kind.EXECUTION_FAILED);
            }
        }));
    }
}
