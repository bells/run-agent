package cn.watsonzhu.runagent.multiagent;

import org.springframework.beans.factory.annotation.Autowired;
import cn.watsonzhu.runagent.reliability.FallbackAction;
import cn.watsonzhu.runagent.reliability.FallbackPolicy;
import cn.watsonzhu.runagent.reliability.ReliabilityExecutor;
import cn.watsonzhu.runagent.reliability.ReliabilityFailure;
import java.util.ArrayList;
import cn.watsonzhu.runagent.performance.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import cn.watsonzhu.runagent.config.MultiAgentProperties;
import cn.watsonzhu.runagent.exception.InvalidRunningQueryException;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.model.MultiAgentRequest;
import cn.watsonzhu.runagent.model.MultiAgentResponse;
import cn.watsonzhu.runagent.observability.RunAgentObservation;
import cn.watsonzhu.runagent.service.RunningDateRange;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

@Service
public class MultiAgentCoordinator {
    private static final Logger log = LoggerFactory.getLogger(MultiAgentCoordinator.class);
    private final CompiledGraph<MultiAgentState> graph;
    private final SupervisorAgent supervisor;
    private final RunningAnalystAgent running;
    private final KnowledgeCoachAgent knowledge;
    private final WeatherAdvisorAgent weather;
    private final MultiAgentProperties properties;
    private final RunAgentObservation observations;
    private final PerformanceGovernance performance;
    private final ReliabilityExecutor reliability;
    private final FallbackPolicy fallback = new FallbackPolicy();

    public MultiAgentCoordinator(SupervisorAgent supervisor, RunningAnalystAgent running, KnowledgeCoachAgent knowledge,
                                  WeatherAdvisorAgent weather, MultiAgentProperties properties, RunAgentObservation observations, PerformanceGovernance performance) {
        this(supervisor, running, knowledge, weather, properties, observations, performance, null);
    }
    @Autowired
    public MultiAgentCoordinator(SupervisorAgent supervisor, RunningAnalystAgent running, KnowledgeCoachAgent knowledge,
            WeatherAdvisorAgent weather, MultiAgentProperties properties, RunAgentObservation observations,
            PerformanceGovernance performance, ReliabilityExecutor reliability) {
        this.reliability = reliability;
        this.supervisor = supervisor;
        this.running = running;
        this.knowledge = knowledge;
        this.weather = weather;
        this.properties = properties;
        this.observations = observations;
        this.performance = performance;
        try {
            this.graph = new StateGraph<>(MultiAgentState.SCHEMA, MultiAgentState::new)
                    .addNode("supervisor", controlled(this::route))
                    .addNode("runningAnalyst", controlled((state, control) -> specialist(state, control, Specialist.RUNNING_ANALYST,
                            () -> running.analyze(state.question(), state.delegatedTask(), state.startDate(), state.endDate(),
                                    new MultiAgentExecutionTrace(state.executionId(), Specialist.RUNNING_ANALYST, control, state.startDate(), state.endDate(), null)))))
                    .addNode("knowledgeCoach", controlled((state, control) -> specialist(state, control, Specialist.KNOWLEDGE_COACH,
                            () -> knowledge.analyze(state.question(), state.delegatedTask(), control))))
                    .addNode("weatherAdvisor", controlled((state, control) -> specialist(state, control, Specialist.WEATHER_ADVISOR,
                            () -> weather.analyze(state.question(), state.delegatedTask(), state.location(),
                                    new MultiAgentExecutionTrace(state.executionId(), Specialist.WEATHER_ADVISOR, control, null, null, state.location())))))
                    .addNode("synthesize", controlled(this::synthesize))
                    .addNode("validateOutput", controlled(this::validateOutput))
                    .addEdge(StateGraph.START, "supervisor")
                    .addConditionalEdges("supervisor", edge_async(state -> state.nextAgent().name()), Map.of(
                            "RUNNING_ANALYST", "runningAnalyst", "KNOWLEDGE_COACH", "knowledgeCoach",
                            "WEATHER_ADVISOR", "weatherAdvisor", "FINISH", "synthesize"))
                    .addEdge("runningAnalyst", "supervisor")
                    .addEdge("knowledgeCoach", "supervisor")
                    .addEdge("weatherAdvisor", "supervisor")
                    .addEdge("synthesize", "validateOutput")
                    .addEdge("validateOutput", StateGraph.END)
                    .compile();
        } catch (GraphStateException exception) {
            throw new IllegalStateException("Multi-agent graph could not be compiled");
        }
    }

    public MultiAgentResponse execute(MultiAgentRequest request) {
        return execute(request, new MultiAgentExecutionControl(properties.timeout()));
    }
    public MultiAgentResponse execute(MultiAgentRequest request, MultiAgentExecutionControl control) {
        return executeBudgeted(request, performance.start(UUID.randomUUID().toString(), control));
    }
    public MultiAgentResponse executeBudgeted(MultiAgentRequest request, ExecutionBudget budget) {
        var control = budget.control();
        String executionId = budget.executionId();
        try {
        return observations.multiAgent(executionId, control::delegationCount, () -> {
            try {
                budget.checkActive();
                validateRequest(request);
                List<Specialist> available = new ArrayList<>();
                if (running.isAvailable()) available.add(Specialist.RUNNING_ANALYST);
                if (knowledge.isAvailable()) available.add(Specialist.KNOWLEDGE_COACH);
                if (weather.isAvailable()) available.add(Specialist.WEATHER_ADVISOR);
                var config = RunnableConfig.builder().addMetadata(MultiAgentExecutionControl.CONFIG_KEY, control).addMetadata(ExecutionBudget.CONFIG_KEY, budget).build();
                var state = graph.invoke(MultiAgentState.initial(request, executionId, available), config)
                        .orElseThrow(() -> new MultiAgentException(MultiAgentException.Kind.EXECUTION_FAILED));
                budget.checkActive();
                return new MultiAgentResponse(executionId, state.delegationPath(),
                        state.completedAgents().stream().map(Enum::name).toList(), state.delegationCount(), state.finalAnswer());
            } catch (RuntimeException exception) {
                budget.checkActive();
                // Async graph wrappers may contain private State. Unwrap only known domain failures.
                Throwable cause = exception;
                while (cause != null) {
                    if (cause instanceof MultiAgentException failure) throw failure;
                    if (cause == cause.getCause()) break;
                    cause = cause.getCause();
                }
                throw new MultiAgentException(MultiAgentException.Kind.EXECUTION_FAILED);
            }
        });
        } finally { performance.finish(budget); }
    }

    private Map<String, Object> route(MultiAgentState state, MultiAgentExecutionControl control) {
        if (ExecutionScope.require().stopOptional()) {
            if (!state.hasEvidence()) throw new MultiAgentException(MultiAgentException.Kind.BUDGET_EXCEEDED);
            return Map.of("nextAgent", Specialist.FINISH, "delegatedTask", "", "delegationPath", List.of("supervisor"));
        }
        if (state.delegationCount() >= properties.maxDelegations()
                || state.availableAgents().stream().allMatch(state.completedAgents()::contains)) {
            requireEvidence(state);
            return Map.of("nextAgent", Specialist.FINISH, "delegatedTask", "", "delegationPath", List.of("supervisor"));
        }
        var decision = observeActive(state, control, "supervisor_route", () -> {
            var result = supervisor.decideNext(state);
            control.checkActive();
            validateDecision(state, result);
            return result;
        });
        if (ExecutionScope.require().stopOptional()) {
            if (!state.hasEvidence()) throw new MultiAgentException(MultiAgentException.Kind.BUDGET_EXCEEDED);
            return Map.of("nextAgent", Specialist.FINISH, "delegatedTask", "", "delegationPath", List.of("supervisor"));
        }
        if (decision.nextAgent() == Specialist.FINISH) requireEvidence(state);
        log.info("multiAgentExecutionId={} agent=supervisor phase=ROUTE nextAgent={} delegation={}",
                state.executionId(), decision.nextAgent(), state.delegationCount());
        return Map.of("nextAgent", decision.nextAgent(), "delegatedTask", decision.task() == null ? "" : decision.task(),
                "delegationPath", List.of("supervisor"));
    }

    private void validateDecision(MultiAgentState state, SupervisorDecision decision) {
        if (decision == null || decision.nextAgent() == null || decision.task() != null && decision.task().length() > properties.maxTaskChars())
            throw new MultiAgentException(MultiAgentException.Kind.INVALID_SUPERVISOR_DECISION);
        if (decision.nextAgent() == Specialist.FINISH) return;
        if (decision.task() == null || decision.task().isBlank() || !state.availableAgents().contains(decision.nextAgent())
                || state.completedAgents().contains(decision.nextAgent()) || state.delegationCount() >= properties.maxDelegations())
            throw new MultiAgentException(MultiAgentException.Kind.INVALID_SUPERVISOR_DECISION);
    }

    private Map<String, Object> specialist(MultiAgentState state, MultiAgentExecutionControl control, Specialist agent, Supplier<String> action) {
        control.delegated();
        String report = "";
        SpecialistStatus status;
        try {
            report = observeActive(state, control, agent.observationName(), () -> {
                String bounded = SpecialistReports.bounded(action.get(), properties.maxReportChars());
                control.checkActive();
                return bounded;
            });
            status = SpecialistStatus.SUCCESS;
        } catch (RuntimeException exception) {
            control.checkActive();
            status = exception instanceof MultiAgentException failure && failure.kind() == MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE
                    || exception instanceof ReliabilityFailure
                    ? SpecialistStatus.UNAVAILABLE : SpecialistStatus.FAILED;
        }
        log.info("multiAgentExecutionId={} agent={} status={}", state.executionId(), agent.nodeName(), status);
        return Map.of(agent.observationName() + "Report", report, agent.observationName() + "Status", status,
                "completedAgents", List.of(agent), "delegationCount", state.delegationCount() + 1,
                "delegationPath", List.of(agent.nodeName()));
    }
    private Map<String, Object> synthesize(MultiAgentState state, MultiAgentExecutionControl control) {
        requireEvidence(state);
        String answer = observeActive(state, control, "supervisor_synthesis", () -> {
            long started = System.nanoTime();
            String status = "FAILED";
            log.info("multiAgentExecutionId={} agent=supervisor phase=SYNTHESIZE status=STARTED", state.executionId());
            try {
                String result = supervisor.synthesize(state);
                control.checkActive();
                if (result == null || result.isBlank()) throw new MultiAgentException(MultiAgentException.Kind.EXECUTION_FAILED);
                status = "SUCCESS";
                return result;
            } finally {
                log.info("multiAgentExecutionId={} agent=supervisor phase=SYNTHESIZE status={} latencyMs={}",
                        state.executionId(), status, java.time.Duration.ofNanos(System.nanoTime() - started).toMillis());
            }
        });
        if (reliability != null && reliability.enabled()
                && fallback.decide(state) == FallbackAction.DEGRADE) {
            reliability.degraded(FallbackAction.DEGRADE);
            answer = answer + "\n\n" + fallback.limitations(state);
        }
        return Map.of("finalAnswer", answer, "delegationPath", List.of("synthesize"));
    }
    private Map<String, Object> validateOutput(MultiAgentState state, MultiAgentExecutionControl control) {
        requireEvidence(state);
        if (state.finalAnswer().isBlank()) throw new MultiAgentException(MultiAgentException.Kind.EXECUTION_FAILED);
        return Map.of();
    }
    private void requireEvidence(MultiAgentState state) {
        if (fallback.decide(state) == FallbackAction.FAIL)
            throw new MultiAgentException(MultiAgentException.Kind.INSUFFICIENT_EVIDENCE);
    }
    private static void validateRequest(MultiAgentRequest request) {
        if (request == null || request.question() == null || request.question().isBlank() || request.question().length() > 4000
                || request.location() != null && request.location().length() > 200)
            throw new MultiAgentException(MultiAgentException.Kind.INVALID_REQUEST);
        if (request.startDate() != null || request.endDate() != null) {
            try { new RunningDateRange(request.startDate(), request.endDate()); }
            catch (InvalidRunningQueryException exception) { throw new MultiAgentException(MultiAgentException.Kind.INVALID_REQUEST); }
        }
    }
    private <T> T observeActive(MultiAgentState state, MultiAgentExecutionControl control, String specialist, Supplier<T> action) {
        ModelStage stage = switch (specialist) {
            case "supervisor_route" -> ModelStage.SUPERVISOR_ROUTE;
            case "supervisor_synthesis" -> ModelStage.SUPERVISOR_SYNTHESIS;
            case "running" -> ModelStage.RUNNING_ANALYST;
            case "knowledge" -> ModelStage.KNOWLEDGE_COACH;
            case "weather" -> ModelStage.WEATHER_ADVISOR;
            default -> throw new IllegalArgumentException("Unknown stage");
        };
        long started = System.nanoTime();
        try {
        return observations.multiAgentSpecialist(state.executionId(), specialist, () -> {
            try {
                T result = action.get();
                control.checkActive();
                return result;
            } catch (RuntimeException exception) {
                control.checkActive();
                throw exception;
            }
        });
        } finally { ExecutionScope.require().stageFinished(stage, java.time.Duration.ofNanos(System.nanoTime() - started)); }
    }

    private AsyncNodeActionWithConfig<MultiAgentState> controlled(
            java.util.function.BiFunction<MultiAgentState, MultiAgentExecutionControl, Map<String, Object>> action) {
        return AsyncNodeActionWithConfig.node_async((state, config) -> {
            var control = (MultiAgentExecutionControl) config.metadata(MultiAgentExecutionControl.CONFIG_KEY).orElseThrow();
            var budget = (ExecutionBudget) config.metadata(ExecutionBudget.CONFIG_KEY).orElseThrow();
            try (var scope = ExecutionScope.open(budget)) {
                control.checkActive();
                Map<String, Object> update = action.apply(state, control);
                control.checkActive();
                return update;
            }
        });
    }
}
