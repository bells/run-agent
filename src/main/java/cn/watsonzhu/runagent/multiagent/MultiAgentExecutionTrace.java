package cn.watsonzhu.runagent.multiagent;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-specialist counters and structured hints, never serialized into MCP metadata. */
public final class MultiAgentExecutionTrace {
    public static final String CONTEXT_KEY = "multiAgentExecution";
    private final String executionId;
    private final Specialist specialist;
    private final MultiAgentExecutionControl control;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final String location;
    private final AtomicInteger steps = new AtomicInteger();
    private final AtomicInteger successes = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger weatherEvidence = new AtomicInteger();
    private final java.util.Set<Coordinates> weatherLocations = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public MultiAgentExecutionTrace(String executionId, Specialist specialist, MultiAgentExecutionControl control,
                                    LocalDate startDate, LocalDate endDate, String location) {
        this.executionId = executionId;
        this.specialist = specialist;
        this.control = control;
        this.startDate = startDate;
        this.endDate = endDate;
        this.location = location;
    }
    public String executionId() { return executionId; }
    public Specialist specialist() { return specialist; }
    public MultiAgentExecutionControl control() { return control; }
    public LocalDate startDate() { return startDate; }
    public LocalDate endDate() { return endDate; }
    public String location() { return location; }
    int nextStep() { return steps.incrementAndGet(); }
    void succeeded(String tool) {
        successes.incrementAndGet();
        if (tool.endsWith("openmeteo_get_forecast") || tool.endsWith("openmeteo_get_air_quality")) weatherEvidence.incrementAndGet();
    }
    void failed() { failures.incrementAndGet(); }
    public int successfulToolCalls() { return successes.get(); }
    boolean hasFailure() { return failures.get() > 0; }
    boolean hasWeatherEvidence() { return weatherEvidence.get() > 0; }
    void allowWeatherLocation(double latitude, double longitude) { weatherLocations.add(new Coordinates(latitude, longitude)); }
    boolean acceptsWeatherLocation(double latitude, double longitude) { return weatherLocations.contains(new Coordinates(latitude, longitude)); }
    boolean hasWeatherLocations() { return !weatherLocations.isEmpty(); }
    private record Coordinates(double latitude, double longitude) { }
}
