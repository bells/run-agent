package cn.watsonzhu.runagent.workflow;

import java.io.Serializable;

/** Internal execution evidence only. Never serialize this record into an HTTP response or log. */
public record KnowledgeEvidence(String sourceTitle, Double score, String text) implements Serializable {
}
