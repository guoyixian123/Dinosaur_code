package dinocode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 执行命令工具（F2）：在工作目录执行 shell 命令，受超时约束；返回 stdout/stderr/退出码。
 */
public final class BashTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record BashArgs(String command) {
    }

    private final Duration timeout;

    public BashTool() {
        this(ToolRegistry.DEFAULT_TIMEOUT);
    }

    /** 包内可见：测试注入极短超时。 */
    BashTool(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        return "在当前工作目录执行 shell 命令，返回标准输出与退出码。命令受超时约束。"
                + "读文件、找文件、搜内容请优先用 ReadFile/Glob/Grep，不要用 Bash 拼凑。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("command", Map.of("type", "string", "description", "要执行的 shell 命令"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("command"));
        return schema;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(Map<String, Object> args) {
        BashArgs a;
        try {
            a = JSON.convertValue(args, BashArgs.class);
        } catch (IllegalArgumentException e) {
            return Result.error("参数错误: " + e.getMessage());
        }
        if (a.command() == null || a.command().isBlank()) {
            return Result.error("缺少参数 command");
        }

        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder pb = win
                ? new ProcessBuilder("cmd", "/C", a.command())
                : new ProcessBuilder("sh", "-c", a.command());
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return Result.error("命令启动失败: " + e.getMessage());
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(out);
            } catch (IOException ignored) {
                // 中断 / 进程销毁路径
            }
        });

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return Result.error("命令被中断");
        }

        if (!finished) {
            process.destroyForcibly();
            return Result.error("命令超时");
        }

        try {
            reader.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("命令被中断");
        }

        String body = "退出码: " + process.exitValue() + "\n" + out;
        return Result.ok(Truncate.byLinesAndBytes(body, Integer.MAX_VALUE, 30000));
    }
}
