package dinocode.prompt;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 环境信息（ch05 F2）：供模型感知当前运行环境。
 * 属变化内容——不进稳定块、不进缓存（F3）；每轮 run 采集一次。
 * 采集有界且可降级（N4）：git 不可用/非 git 目录留空；不读任何环境变量（N5）。
 */
public record Environment(
        String workingDir,
        String platform,
        String date,
        String gitStatus,
        String version,
        String model) {

    public static Environment gather(String version, String model) {
        return new Environment(
                System.getProperty("user.dir", ""),
                System.getProperty("os.name", ""),
                java.time.LocalDate.now().toString(),
                readGitStatus(),
                version == null ? "" : version,
                model == null ? "" : model);
    }

    /** 渲染为「环境信息」段：逐行 Key: Value，空值项省略（AC13 降级）。 */
    public String render() {
        StringBuilder sb = new StringBuilder("环境信息：");
        appendLine(sb, "工作目录", workingDir);
        appendLine(sb, "平台", platform);
        appendLine(sb, "当前日期", date);
        appendLine(sb, "git 状态", gitStatus);
        appendLine(sb, "应用版本", version);
        appendLine(sb, "当前模型", model);
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        sb.append('\n').append(key).append(": ").append(value);
    }

    /** `git status --porcelain` 摘要；2s 超时 / 非 git 目录 / 失败 → 空串（N4）。 */
    private static String readGitStatus() {
        Process process = null;
        try {
            process = new ProcessBuilder("git", "status", "--porcelain")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) {
                return "";
            }
            String out = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).strip();
            if (out.isEmpty()) {
                return "工作区干净";
            }
            String[] lines = out.split("\n");
            return lines.length == 1 ? lines[0] : (lines.length + " 个文件改动");
        } catch (IOException e) {
            return ""; // git 不可用（N4 降级）
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return "";
        }
    }
}
