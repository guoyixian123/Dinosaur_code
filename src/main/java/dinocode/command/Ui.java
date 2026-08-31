package dinocode.command;

import dinocode.permission.Mode;

import java.util.List;

/**
 * handler 操作 TUI 的唯一通道（ch10 F33/F34）：只暴露命令实际需要的能力，
 * 不外露 TUI 内部字段。Tui 实现此接口（G3 解耦）。
 *
 * <p>降级合约：实现需对未就绪组件做防御——provider/writer/memory 未注入时
 * 查询返回零值/空串/空表，动作方法内部兜底报错。
 */
public interface Ui {

    // ---------- 输出 ----------

    /** 向用户输出一条普通提示。 */
    void println(String msg);

    /** 向用户输出一条错误提示。 */
    void error(String msg);

    // ---------- 模式 ----------

    Mode mode();

    void setMode(Mode m);

    // ---------- 提示词注入（KindPrompt 命令，F11/N3） ----------

    /** 注入一条 user 消息（presetPrompt 进 history 并持久化）并立即触发 LLM 回合。 */
    void injectAndSend(String displayLabel, String presetPrompt);

    // ---------- 只读查询 ----------

    long usageIn();

    long usageOut();

    String modelName();

    String cwd();

    int toolCount();

    /** 两级记忆文件名清单（项目级 + 用户级，/memory 数据源）。 */
    List<String> memoryFiles();

    String sessionPath();

    String sessionId();

    // ---------- 影响界面动作 ----------

    /** 退出进程（N12：先取消根 cancellation token 再退出）。 */
    void quit();

    /** 手动触发上下文压缩（ch08 /compact 复用）。 */
    void forceCompact();

    /** 打开会话恢复列表（ch09 /resume 复用）。 */
    void openResumeMenu();

    /** 清空当前会话并开启新会话（/clear，F17）。 */
    void clearAndNewSession();

    /** 主循环是否空闲（idle 守护，N3a）。 */
    boolean idle();

    /** 全 no-op 测试桩。 */
    final class NopUi implements Ui {
        public static final NopUi INSTANCE = new NopUi();

        private NopUi() {
        }

        @Override
        public void println(String msg) {
        }

        @Override
        public void error(String msg) {
        }

        @Override
        public Mode mode() {
            return Mode.DEFAULT;
        }

        @Override
        public void setMode(Mode m) {
        }

        @Override
        public void injectAndSend(String displayLabel, String presetPrompt) {
        }

        @Override
        public long usageIn() {
            return 0;
        }

        @Override
        public long usageOut() {
            return 0;
        }

        @Override
        public String modelName() {
            return "";
        }

        @Override
        public String cwd() {
            return "";
        }

        @Override
        public int toolCount() {
            return 0;
        }

        @Override
        public List<String> memoryFiles() {
            return List.of();
        }

        @Override
        public String sessionPath() {
            return "";
        }

        @Override
        public String sessionId() {
            return "";
        }

        @Override
        public void quit() {
        }

        @Override
        public void forceCompact() {
        }

        @Override
        public void openResumeMenu() {
        }

        @Override
        public void clearAndNewSession() {
        }

        @Override
        public boolean idle() {
            return true;
        }
    }
}
