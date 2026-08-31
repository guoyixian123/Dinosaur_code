package dinocode.agent;

/**
 * Agent Loop 内置常量（ch04 F2，不可配）。
 */
final class AgentConstants {

    /** 迭代上限兜底（F2）。 */
    static final int MAX_ITERATIONS = 25;

    /** 连续「整轮只产生未知工具调用」上限（F2）。 */
    static final int MAX_UNKNOWN_RUN = 3;

    static final String NOTICE_MAX_ITER = "(已达最大迭代轮数 " + MAX_ITERATIONS + "，自动停止；可继续发消息推进。)";
    static final String NOTICE_UNKNOWN_TOOLS = "(连续多轮只请求到未注册的工具，自动停止。)";
    static final String NOTICE_STREAM_ERR = "(请求出错，本轮已中断。)";
    static final String NOTICE_CANCELLED = "(已取消。)";
}
