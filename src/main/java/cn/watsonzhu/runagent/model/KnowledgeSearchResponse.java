package cn.watsonzhu.runagent.model;

import java.util.List;
public record KnowledgeSearchResponse(String queryId, List<KnowledgeSearchMatch> matches) { }
