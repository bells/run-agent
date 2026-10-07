package cn.watsonzhu.runagent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record KnowledgeSearchRequest(@NotBlank @Size(max = 2000) String query) { }
