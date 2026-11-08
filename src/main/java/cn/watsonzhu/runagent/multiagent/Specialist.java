package cn.watsonzhu.runagent.multiagent;

public enum Specialist {
    RUNNING_ANALYST("runningAnalyst", "running"),
    KNOWLEDGE_COACH("knowledgeCoach", "knowledge"),
    WEATHER_ADVISOR("weatherAdvisor", "weather"),
    FINISH("synthesize", "supervisor_synthesis");

    private final String nodeName;
    private final String observationName;
    Specialist(String nodeName, String observationName) {
        this.nodeName = nodeName;
        this.observationName = observationName;
    }
    public String nodeName() { return nodeName; }
    public String observationName() { return observationName; }
}
