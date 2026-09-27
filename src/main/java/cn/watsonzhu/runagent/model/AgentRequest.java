package cn.watsonzhu.runagent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AgentRequest(
        @Size(max = 100, message = "conversationId must not exceed 100 characters")
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "conversationId must contain only letters, digits, - or _")
        String conversationId,
        @NotBlank(message = "message must not be blank")
        @Size(max = 4_000, message = "message must not exceed 4000 characters")
        String message
) {
}
