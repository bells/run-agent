package cn.watsonzhu.runagent.reliability;

import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Retry is restricted to six accepted read operations and their original dependency identity. */
public final class ReliableToolCallback implements ToolCallback {
    private static final Set<String> RUNNING=Set.of("getRunningSummary","getRecentRuns","getPersonalBest");
    private static final Set<String> WEATHER=Set.of("openmeteo_search_locations","openmeteo_get_forecast","openmeteo_get_air_quality");
    private final ToolCallback delegate;
    private final Dependency dependency;
    private final OperationSafety safety;
    private final ReliabilityExecutor executor;
    private final ReliabilityDeadline deadline;
    public ReliableToolCallback(ToolCallback delegate,Dependency dependency,String originalName,ReliabilityExecutor executor,ReliabilityDeadline deadline) {
        this.delegate=delegate;this.dependency=dependency;this.executor=executor;this.deadline=deadline;
        this.safety=originalName!=null && (dependency==Dependency.RUNNING_MCP && RUNNING.contains(originalName)
                || dependency==Dependency.WEATHER_MCP && WEATHER.contains(originalName))?OperationSafety.READ_ONLY:OperationSafety.NON_IDEMPOTENT;
    }
    @Override public ToolDefinition getToolDefinition(){return delegate.getToolDefinition();}
    @Override public ToolMetadata getToolMetadata(){return delegate.getToolMetadata();}
    @Override public String call(String input){return executor.execute(dependency,safety,deadline,()->delegate.call(input));}
    @Override public String call(String input,ToolContext context){return executor.execute(dependency,safety,deadline,()->delegate.call(input,context));}
}
