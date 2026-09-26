package cn.watsonzhu.runagent.exception;

/** 文件缺失或数据损坏时使用固定消息，不向模型透露本机路径和解析细节。 */
public class RunningDataUnavailableException extends RuntimeException {

    public RunningDataUnavailableException(String message) {
        super(message);
    }
}
