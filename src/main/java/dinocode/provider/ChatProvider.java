package dinocode.provider;

/**
 * Provider 统一接口。新增后端 = 新增一个实现 + 一个协议标识（见 spec）。
 */
public interface ChatProvider {

    /** 发起一轮对话，返回事件流。本方法本身不抛业务异常；错误以 Failure 事件表达。 */
    EventStream chat(ChatRequest request);

    String model();

    String baseUrl();
}
