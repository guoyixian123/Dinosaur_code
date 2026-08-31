package dinocode.provider;

import dinocode.core.Message;
import dinocode.core.ToolDefinition;

import java.util.List;

/**
 * 一轮对话的请求参数（ch05 F3/F6）。模型/地址/密钥在 Provider 构造时已定（来自配置）。
 *
 * <p>系统内容分两段（F3 缓存通道）：{@code systemStable} 是稳定系统提示，
 * 跨轮逐字节一致、进缓存前缀；{@code systemEnvironment} 是环境信息段，
 * 每轮可能变化、不进缓存。{@code reminder} 是本轮动态构造的补充消息
 * （已含 system-reminder 标签），织入消息通道、不写入历史（N3）。
 *
 * @param maxTokens        本轮最大输出
 * @param tools            工具定义（空 = 不带工具；规划模式 = 只读子集）
 * @param systemStable     稳定系统提示（可缓存），空串 = 不发 system
 * @param systemEnvironment 环境信息段（不缓存），空串 = 省略
 * @param reminder         本轮补充消息（已含标签），空串 = 不注入
 */
public record ChatRequest(
        List<Message> history,
        int maxTokens,
        List<ToolDefinition> tools,
        String systemStable,
        String systemEnvironment,
        String reminder) {

    public ChatRequest {
        history = List.copyOf(history);
        tools = List.copyOf(tools);
        systemStable = systemStable == null ? "" : systemStable;
        systemEnvironment = systemEnvironment == null ? "" : systemEnvironment;
        reminder = reminder == null ? "" : reminder;
    }

    /** 不带系统内容与 reminder 的便捷构造（测试 / 纯对话）。 */
    public ChatRequest(List<Message> history, int maxTokens, List<ToolDefinition> tools) {
        this(history, maxTokens, tools, "", "", "");
    }
}
