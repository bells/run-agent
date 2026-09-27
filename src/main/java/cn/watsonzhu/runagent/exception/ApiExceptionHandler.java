package cn.watsonzhu.runagent.exception;

import java.time.Instant;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ApiError> handleValidation(WebExchangeBindException exception, ServerWebExchange exchange) {
        String message = exception.getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Request validation failed");
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, exchange);
    }

    @ExceptionHandler({ConstraintViolationException.class, ServerWebInputException.class})
    public ResponseEntity<ApiError> handleInvalidRequest(Exception exception, ServerWebExchange exchange) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request parameters", exchange);
    }

    @ExceptionHandler(StructuredOutputException.class)
    public ResponseEntity<ApiError> handleStructuredOutput(StructuredOutputException exception,
                                                            ServerWebExchange exchange) {
        log.warn("Structured output processing failed: {}", exception.getMessage());
        return response(HttpStatus.BAD_GATEWAY, "STRUCTURED_OUTPUT_ERROR",
                "The AI response could not be converted to a running intent", exchange);
    }

    @ExceptionHandler(AiRequestException.class)
    public ResponseEntity<ApiError> handleAiRequest(AiRequestException exception, ServerWebExchange exchange) {
        // 对外只给稳定错误码和安全文案；底层模型异常可能包含供应商细节。
        log.warn("AI request failed: {}", exception.getMessage());
        return response(HttpStatus.BAD_GATEWAY, "AI_SERVICE_ERROR",
                "The AI service is temporarily unavailable", exchange);
    }

    @ExceptionHandler(AgentTimeoutException.class)
    public ResponseEntity<ApiError> handleAgentTimeout(AgentTimeoutException exception, ServerWebExchange exchange) {
        return response(HttpStatus.GATEWAY_TIMEOUT, "AGENT_TIMEOUT", "The agent request timed out", exchange);
    }

    @ExceptionHandler(AgentLimitException.class)
    public ResponseEntity<ApiError> handleAgentLimit(AgentLimitException exception, ServerWebExchange exchange) {
        return response(HttpStatus.BAD_GATEWAY, "AGENT_TOOL_LIMIT", "The agent stopped after reaching its tool limit", exchange);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, ServerWebExchange exchange) {
        log.error("Unexpected request failure", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", exchange);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message,
                                               ServerWebExchange exchange) {
        ApiError error = new ApiError(
                Instant.now(),
                status.value(),
                code,
                message,
                exchange.getRequest().getPath().value());
        return ResponseEntity.status(status).body(error);
    }
}
