package cn.watsonzhu.runagent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record KnowledgeQuestionRequest(@NotBlank @Size(max = 2000) String question) { }
