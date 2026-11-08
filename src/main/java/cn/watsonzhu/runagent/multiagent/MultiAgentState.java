package cn.watsonzhu.runagent.multiagent;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import cn.watsonzhu.runagent.model.MultiAgentRequest;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

public final class MultiAgentState extends AgentState {
    public static final Map<String, Channel<?>> SCHEMA;
    static {
        Map<String, Channel<?>> channels = new HashMap<>();
        for (String key : List.of("executionId", "question", "startDate", "endDate", "location", "availableAgents",
                "nextAgent", "delegatedTask", "delegationCount", "runningReport", "knowledgeReport", "weatherReport",
                "runningStatus", "knowledgeStatus", "weatherStatus", "finalAnswer"))
            channels.put(key, Channels.base((previous, update) -> update));
        channels.put("completedAgents", Channels.<Specialist>appender(ArrayList::new));
        channels.put("delegationPath", Channels.<String>appenderWithDuplicate(ArrayList::new));
        SCHEMA = Map.copyOf(channels);
    }

    public MultiAgentState(Map<String, Object> data) { super(data); }
    public static Map<String, Object> initial(MultiAgentRequest request, String executionId, List<Specialist> available) {
        Map<String, Object> data = new HashMap<>();
        data.put("executionId", executionId);
        data.put("question", request.question());
        data.put("startDate", request.startDate());
        data.put("endDate", request.endDate());
        data.put("location", request.location());
        data.put("availableAgents", new ArrayList<>(available));
        data.put("delegationCount", 0);
        for (Specialist agent : List.of(Specialist.RUNNING_ANALYST, Specialist.KNOWLEDGE_COACH, Specialist.WEATHER_ADVISOR))
            data.put(agent.observationName() + "Status", available.contains(agent) ? SpecialistStatus.NOT_RUN : SpecialistStatus.UNAVAILABLE);
        return data;
    }
    public String executionId() { return this.<String>value("executionId").orElseThrow(); }
    public String question() { return this.<String>value("question").orElseThrow(); }
    public LocalDate startDate() { return this.<LocalDate>value("startDate").orElse(null); }
    public LocalDate endDate() { return this.<LocalDate>value("endDate").orElse(null); }
    public String location() { return this.<String>value("location").orElse(null); }
    public List<Specialist> availableAgents() { return List.copyOf(this.<List<Specialist>>value("availableAgents").orElse(List.of())); }
    public Specialist nextAgent() { return this.<Specialist>value("nextAgent").orElseThrow(); }
    public String delegatedTask() { return this.<String>value("delegatedTask").orElse(""); }
    public int delegationCount() { return this.<Integer>value("delegationCount").orElse(0); }
    public List<Specialist> completedAgents() { return List.copyOf(this.<List<Specialist>>value("completedAgents").orElse(List.of())); }
    public List<String> delegationPath() { return List.copyOf(this.<List<String>>value("delegationPath").orElse(List.of())); }
    public String report(Specialist agent) { return this.<String>value(agent.observationName() + "Report").orElse(""); }
    public SpecialistStatus status(Specialist agent) {
        return this.<SpecialistStatus>value(agent.observationName() + "Status").orElse(SpecialistStatus.NOT_RUN);
    }
    public String finalAnswer() { return this.<String>value("finalAnswer").orElse(""); }
    public boolean hasEvidence() { return completedAgents().stream().anyMatch(a -> status(a) == SpecialistStatus.SUCCESS); }
    @Override public String toString() { return "MultiAgentState[executionId=" + executionId() + "]"; }
}
