package dinocode.hook;

import java.util.Map;

/**
 * Hook 动作（ch12 F16~F26）：shell / prompt / http / subagent 四类（sealed，G7）。
 */
public sealed interface Action permits Action.Shell, Action.Prompt, Action.Http, Action.Subagent {

    /** shell 命令（F17~F19）：payload JSON 走 stdin；拦截事件下 exit 2 = 拦截。 */
    record Shell(String command) implements Action {
    }

    /** 提示词注入（F20~F22）：文本进入下一轮 reminder 队列，永不表达拦截。 */
    record Prompt(String text) implements Action {
    }

    /** HTTP 请求（F23~F25）：缺省 body 时序列化 payload；拦截事件下 decision=block 拦截。 */
    record Http(String url, String method, Map<String, String> headers, String bodyTemplate) implements Action {
    }

    /** 子 Agent（F26）：本期占位——加载校验字段，执行仅打日志（N8）。 */
    record Subagent(String agentName, String prompt) implements Action {
    }
}
