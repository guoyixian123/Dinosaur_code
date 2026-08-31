package dinocode.compact;

/** 摘要请求撞上下文上限的哨兵异常（触发 F27 丢消息组重试）。 */
public class PromptTooLongException extends Exception {

    public PromptTooLongException(String message) {
        super(message);
    }
}
