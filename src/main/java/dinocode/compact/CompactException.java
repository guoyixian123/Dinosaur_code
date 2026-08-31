package dinocode.compact;

/** 压缩流程 checked 异常：摘要失败、PTL 重试用光等。 */
public class CompactException extends Exception {

    public CompactException(String message) {
        super(message);
    }

    public CompactException(String message, Throwable cause) {
        super(message, cause);
    }
}
