package dinocode.tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程执行公共壳（ch16 前置重构）：边读边等，防止「先 waitFor 后读输出」的
 * 管道缓冲区死锁——子进程 stdout/stderr 写满 64KB 管道缓冲而无人读取时会永远
 * 阻塞在写管道上，waitFor 等不到退出，最终被误判超时（WorktreeManager 与
 * HookExecutor 各踩过一次同一模式）。
 */
public final class ProcessRunner {

    /** 一次执行的结果；exitCode 为 null 表示超时被强杀。 */
    public record Output(Integer exitCode, String stdout, String stderr) {
        public boolean timedOut() {
            return exitCode == null;
        }
    }

    private ProcessRunner() {
    }

    /**
     * 跑一个进程并收集 stdout/stderr：异步排干两路输出，主线程限时等待退出。
     *
     * @param timeoutSeconds 超时秒数；到点 destroyForcibly 并返回 timedOut()
     */
    public static Output run(ProcessBuilder pb, long timeoutSeconds)
            throws IOException, InterruptedException {
        return run(pb, timeoutSeconds, null);
    }

    /**
     * 同上，但先向 stdin 写入一段数据再关闭（如 hook 的 payload 单行 JSON 契约）。
     * stdin 写入失败按忽略处理——命令提前退出（如 head）不影响判定。
     */
    public static Output run(ProcessBuilder pb, long timeoutSeconds, String stdin)
            throws IOException, InterruptedException {
        Process process = pb.start();
        if (stdin != null) {
            try (var out = process.getOutputStream()) {
                out.write(stdin.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException ignored) {
                // 命令提前退出（如 head）：写管道失败不影响判定
            }
        }
        AtomicReference<String> stdoutRef = new AtomicReference<>("");
        AtomicReference<String> stderrRef = new AtomicReference<>("");
        Thread outReader = Thread.ofVirtual().start(() ->
                stdoutRef.set(drain(process.getInputStream())));
        Thread errReader = Thread.ofVirtual().start(() ->
                stderrRef.set(drain(process.getErrorStream())));
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            outReader.join(1000);
            errReader.join(1000);
            return new Output(null, stdoutRef.get(), stderrRef.get());
        }
        outReader.join(1000);
        errReader.join(1000);
        return new Output(process.exitValue(), stdoutRef.get(), stderrRef.get());
    }

    private static String drain(java.io.InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        } catch (IOException ignored) {
            // 进程被强杀时流关闭，读到多少算多少
        }
        return sb.toString();
    }
}
