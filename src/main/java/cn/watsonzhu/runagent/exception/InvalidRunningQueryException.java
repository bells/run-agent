package cn.watsonzhu.runagent.exception;

/** 模型给出的日期参数不合法；消息只包含可公开的校验规则。 */
public class InvalidRunningQueryException extends RuntimeException {

    public InvalidRunningQueryException(String message) {
        super(message);
    }
}
