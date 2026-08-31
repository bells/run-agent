package cn.watsonzhu.runagent.exception;

public class AiRequestException extends RuntimeException {

    public AiRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
