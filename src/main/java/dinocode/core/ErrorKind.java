package dinocode.core;

/**
 * 错误分类。决定终端提示的措辞（具体文案见 checklist §G）。
 */
public enum ErrorKind {
    /** 认证失败（401/403） */
    AUTH,
    /** 限流（429） */
    RATE_LIMIT,
    /** 网络异常：拒连、超时、流中途断开 */
    NETWORK,
    /** 上下文超限 */
    CONTEXT_OVERFLOW,
    /** 其余 */
    OTHER
}
