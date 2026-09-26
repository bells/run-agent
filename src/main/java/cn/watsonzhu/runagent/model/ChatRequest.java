package cn.watsonzhu.runagent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** HTTP 入参先由 Bean Validation 限制为空或过长的消息，再进入 AI 服务层。 */
public record ChatRequest(
        @NotBlank(message = "message must not be blank")
        @Size(max = 4_000, message = "message must not exceed 4000 characters")
        String message
) {
}
