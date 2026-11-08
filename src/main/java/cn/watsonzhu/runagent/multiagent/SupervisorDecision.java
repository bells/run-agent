package cn.watsonzhu.runagent.multiagent;

public record SupervisorDecision(Specialist nextAgent, String task) { }
