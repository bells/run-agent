package cn.watsonzhu.runagent.evaluation;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import cn.watsonzhu.runagent.config.*;
import cn.watsonzhu.runagent.exception.MultiAgentException;
import cn.watsonzhu.runagent.model.MultiAgentRequest;
import cn.watsonzhu.runagent.model.MultiAgentResponse;
import cn.watsonzhu.runagent.multiagent.*;
import cn.watsonzhu.runagent.performance.*;
import cn.watsonzhu.runagent.observability.ObservationTestSupport;
import cn.watsonzhu.runagent.rag.KnowledgeSearchService;
import cn.watsonzhu.runagent.tool.RunningTools;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.*;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import static cn.watsonzhu.runagent.evaluation.EvaluationModels.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Shared synthetic environment for offline evaluation and offline/live performance runs. */
public final class MultiAgentScenario {
    private final Set<String> successful=new HashSet<>();
    private final List<Boolean> isolation=new ArrayList<>();
    private final List<Boolean> toolChecks=new ArrayList<>();
    private final Set<String> invokedToolRoles=new HashSet<>();
    private final Set<String> attemptedToolRoles=new HashSet<>();
    private final MultiAgentCoordinator coordinator;
    private final ChatModel liveModel;
    private final ExecutionBudget budget;
    public MultiAgentScenario(EvaluationFixtures fixture, MultiAgentScript script, PerformanceProperties performance, ChatModel liveModel, Duration timeout) {
        if(script==null)throw new IllegalArgumentException("Missing independent model script");
        this.liveModel=liveModel;
        var properties=new MultiAgentProperties(timeout,3,2500,2000);
        budget=new ExecutionBudget(UUID.randomUUID().toString(),new MultiAgentExecutionControl(timeout),performance);
        var manager=ToolCallingManager.builder().maxCallsPerTool(4).maxTotalToolCalls(8).build();
        var round=new AtomicInteger();
        ChatModel supervisor=model("supervisor",prompt->{
            if(prompt.getSystemMessage().getText().contains("final RunAgent supervisor"))return answer("Synthetic integrated advice with unavailable evidence limitations.");
            return answer("{\"nextAgent\":\""+script.decisions().get(round.getAndIncrement())+"\",\"task\":\"轻松跑 synthetic subtask\"}");
        });
        var callbacks=Arrays.stream(ToolCallbacks.from(new RunningTools(fixture.running))).map(callback->new ToolCallback(){
            public ToolDefinition getToolDefinition(){return callback.getToolDefinition();}
            public String call(String input){
                attemptedToolRoles.add("running");
                String result;
                try { result=callback.call(input,new org.springframework.ai.chat.model.ToolContext(Map.of("requestType","multi-agent"))); }
                catch(RuntimeException exception) { toolChecks.add(false); throw exception; }
                toolChecks.add(true);
                if("RUNNING_ANALYST".equals(script.failedSpecialist()))throw new IllegalStateException("synthetic dependency unavailable");
                return result;
            }
        }).toArray(ToolCallback[]::new);
        var running=new RunningAnalystAgent(toolModel("running"),fixture.prompts,()->callbacks,manager,properties,ObservationRegistry.NOOP);
        var weatherTools=List.of("openmeteo_search_locations","openmeteo_get_forecast","openmeteo_get_air_quality").stream().map(name->weatherTool(name,script)).toArray(ToolCallback[]::new);
        var weather=new WeatherAdvisorAgent(toolModel("weather"),fixture.prompts,()->weatherTools,manager,properties,ObservationRegistry.NOOP);
        KnowledgeSearchService search=fixture.search;
        if("KNOWLEDGE_COACH".equals(script.failedSpecialist())) {
            search=mock(KnowledgeSearchService.class);when(search.isAvailable()).thenReturn(true);
            when(search.retrieveDocuments(anyString(),anyString())).thenThrow(new MultiAgentException(MultiAgentException.Kind.DEPENDENCY_UNAVAILABLE));
        }
        var knowledge=new KnowledgeCoachAgent(model("knowledge",p->answer("Synthetic training principles from retrieved evidence.")),fixture.prompts,search,properties,ObservationRegistry.NOOP);
        var observations=ObservationTestSupport.noop();
        coordinator=new MultiAgentCoordinator(new SupervisorAgent(supervisor,fixture.prompts,ObservationRegistry.NOOP),running,knowledge,weather,properties,observations,new PerformanceGovernance(performance,observations));
    }
    public MultiAgentResponse execute(MultiAgentCase item) {
        return coordinator.executeBudgeted(new MultiAgentRequest(item.question(),LocalDate.parse(item.startDate()),LocalDate.parse(item.endDate()),item.location()),budget);
    }
    public PerformanceSnapshot snapshot(){return PerformanceSnapshot.capture(budget,new CostEstimator(budget.properties().pricing()));}
    public Set<String> successful(){return Set.copyOf(successful);}
    public boolean isolated(){return !isolation.isEmpty() && isolation.stream().allMatch(Boolean::booleanValue);}
    public boolean toolContract(){return attemptedToolRoles.containsAll(invokedToolRoles) && toolChecks.stream().allMatch(Boolean::booleanValue);}
    private ChatModel model(String role, Function<Prompt,ChatResponse> script) {
        return new ChatModel(){
            public ChatOptions getOptions(){return liveModel==null?ToolCallingChatOptions.builder().build():liveModel.getOptions();}
            public ChatResponse call(Prompt prompt){
                if(role.equals("running") || role.equals("weather"))invokedToolRoles.add(role);
                Set<String> tools=prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks()!=null
                        ? options.getToolCallbacks().stream().map(t->t.getToolDefinition().name()).collect(java.util.stream.Collectors.toSet()):Set.of();
                Set<String> expected=switch(role){case "running"->Set.of("getRunningSummary","getRecentRuns","getPersonalBest");
                    case "weather"->Set.of("openmeteo_search_locations","openmeteo_get_forecast","openmeteo_get_air_quality");default->Set.of();};
                isolation.add(tools.equals(expected));
                if("supervisor".equals(role) && prompt.getSystemMessage().getText().contains("final RunAgent supervisor")) {
                    for(var specialist:List.of(Specialist.RUNNING_ANALYST,Specialist.KNOWLEDGE_COACH,Specialist.WEATHER_ADVISOR))
                        if(prompt.getUserMessage().getText().contains(specialist.name()+" = SUCCESS"))successful.add(specialist.name());
                }
                if(liveModel!=null)return liveModel.call(prompt);
                java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
                return script.apply(prompt);
            }
        };
    }
    private ChatModel toolModel(String role) {
        var round=new AtomicInteger();
        return model(role,p->{
            int step=round.getAndIncrement();
            if(role.equals("running") && step==0)return toolCall("getRunningSummary","{\"startDate\":\"2026-01-01\",\"endDate\":\"2026-01-31\"}");
            if(role.equals("weather") && step==0)return toolCall("openmeteo_search_locations","{\"name\":\"Synthetic City\"}");
            if(role.equals("weather") && step==1)return toolCall("openmeteo_get_forecast","{\"latitude\":1,\"longitude\":2}");
            return answer("Synthetic concise "+role+" evidence report.");
        });
    }
    private ToolCallback weatherTool(String name, MultiAgentScript script) {
        boolean search=name.endsWith("search_locations");
        return new ToolCallback(){
            public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name(name).description(search?"Search synthetic locations":"Read fixed synthetic weather")
                .inputSchema(search?"{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}":
                    "{\"type\":\"object\",\"properties\":{\"latitude\":{\"type\":\"number\"},\"longitude\":{\"type\":\"number\"}},\"required\":[\"latitude\",\"longitude\"]}").build();}
            public String call(String input){
                attemptedToolRoles.add("weather");
                var args=EvaluationFixtures.JSON.readTree(input);
                boolean valid=search?"Synthetic City".equals(args.path("name").asText()):args.path("latitude").asDouble()==1 && args.path("longitude").asDouble()==2;
                toolChecks.add(valid);
                if(!valid)throw new IllegalArgumentException("Synthetic tool contract failed");
                if("WEATHER_ADVISOR".equals(script.failedSpecialist()))throw new IllegalStateException("synthetic dependency unavailable");
                return search?"{\"latitude\":1,\"longitude\":2}":"{\"temperature\":18,\"synthetic\":true}";
            }
        };
    }
    private static ChatResponse answer(String text){return response(new AssistantMessage(text));}
    private static ChatResponse toolCall(String name,String args){return response(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("synthetic-call","function",name,args))).build());}
    private static ChatResponse response(AssistantMessage message){return new ChatResponse(List.of(new Generation(message)),ChatResponseMetadata.builder().usage(new DefaultUsage(100,20)).build());}
}
