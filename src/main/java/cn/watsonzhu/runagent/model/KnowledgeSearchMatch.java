package cn.watsonzhu.runagent.model;

public record KnowledgeSearchMatch(int rank, Double score, String documentId, String sourceTitle,
        String pageNumber, String chapter, String preview) { }
