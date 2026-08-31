package dinocode.compact;

/**
 * 上下文管理硬编码常量（ch08 F36）。调整属于代码变更，不暴露为配置项。
 */
public final class CompactConstants {

    /** 单条工具结果落盘阈值（字节，UTF-8 口径）。 */
    public static final int SINGLE_RESULT_LIMIT = 50000;
    /** 单条 RoleTool 消息内工具结果聚合阈值（字节）。 */
    public static final int MESSAGE_AGGREGATE_LIMIT = 200000;
    /** 给摘要 LLM 输出预留的 token 空间。 */
    public static final int SUMMARY_RESERVE = 20000;
    /** 自动触发的额外安全余量：防估算误差与单轮波动。 */
    public static final int AUTO_SAFETY_MARGIN = 13000;
    /** 手动触发的安全余量：只用来判断摘要请求本身能不能塞下。 */
    public static final int MANUAL_SAFETY_MARGIN = 3000;
    /** 恢复段最多展示几个文件。 */
    public static final int RECOVERY_FILE_LIMIT = 5;
    /** 单个文件快照的 token 上限，超出时保留头部、截掉尾部。 */
    public static final int RECOVERY_TOKENS_PER_FILE = 5000;
    /** 摘要后保留近期原文的 token 下界。 */
    public static final int RECENT_KEEP_TOKENS = 10000;
    /** 摘要后保留近期原文的条数下界。 */
    public static final int RECENT_KEEP_MESSAGES = 5;
    /** 自动摘要熔断阈值（连续失败次数）。 */
    public static final int MAX_CONSECUTIVE_AUTO_COMPACT_FAILURES = 3;
    /** 摘要请求自身 PTL 的直接重试次数。 */
    public static final int PTL_RETRY_LIMIT = 3;
    /** PTL 直接重试用光后每次丢弃的分组比例。 */
    public static final double PTL_DROP_PERCENTAGE = 0.2;
    /** 增量估算的字符/token 比（锚定真实 usage 后的近似）。 */
    public static final double ESTIMATE_CHARS_PER_TOKEN = 3.5;
    /** 预览体头部字节数上限。 */
    public static final int PREVIEW_HEAD_BYTES = 2048;
    /** 预览体头部行数上限。 */
    public static final int PREVIEW_HEAD_LINES = 20;

    private CompactConstants() {
    }
}
