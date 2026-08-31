package dinocode.instructions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 项目指令文件加载器（ch09 F1~F8 / MEWCODE.md）：
 * 三层路径按优先级扫描、@include 展开（嵌套 ≤5 层 + 环路检测 + 路径逃逸检测 + 二进制跳过）。
 * 加载失败一律降级为空，不阻塞启动（N5）。
 */
public final class Loader {

    private static final Pattern INCLUDE_LINE = Pattern.compile("^@include (.+)$");
    private static final int MAX_DEPTH = 5;
    private static final int BINARY_SNIFF_BYTES = 512;

    private final Path projectRoot;
    private final Path userHome;

    public Loader(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.userHome = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
    }

    /** 三层指令拼接（项目根 → 项目配置级 → 用户级；高优先级在前）。 */
    public String load() {
        List<String> parts = new ArrayList<>();
        Path projectBoundary = projectRoot;
        Path userBoundary = userHome.resolve(".dino");
        // ① 项目级 ② 项目配置级 ③ 用户级（F1）
        addLayer(parts, projectRoot.resolve("MEWCODE.md"), projectBoundary);
        addLayer(parts, projectRoot.resolve(".dino").resolve("MEWCODE.md"), projectBoundary);
        addLayer(parts, userBoundary.resolve("MEWCODE.md"), userBoundary);
        return String.join("\n\n", parts);
    }

    private void addLayer(List<String> parts, Path file, Path boundary) {
        if (!Files.isRegularFile(file)) {
            return; // 缺失静默跳过（F6/AC2）
        }
        try {
            String text = loadFile(file, boundary, 1, new HashSet<>());
            if (text != null && !text.isBlank()) {
                parts.add(text.strip());
            }
        } catch (IOException e) {
            // 单层加载失败降级为空（N5），不阻塞启动
            System.err.println("[instructions] warn: 加载失败 " + file + ": " + e.getMessage());
        }
    }

    /** 单文件加载 + @include 展开（F2~F6）。 */
    String loadFile(Path file, Path boundary, int depth, Set<Path> visited) throws IOException {
        if (depth > MAX_DEPTH) {
            return "<!-- @include 超过最大嵌套深度，已跳过: " + file.getFileName() + " -->";
        }
        Path absolute = file.toAbsolutePath().normalize();
        if (visited.contains(absolute)) {
            return "<!-- @include 检测到环路，已跳过: " + file.getFileName() + " -->";
        }
        if (!absolute.startsWith(boundary)) {
            return "<!-- @include 路径超出允许范围，已跳过: " + absolute + " -->";
        }
        if (!Files.isRegularFile(absolute)) {
            return ""; // 找不到静默跳过（F6）
        }
        byte[] raw = Files.readAllBytes(absolute);
        if (isBinary(raw)) {
            return "<!-- @include 二进制文件不可读，已跳过: " + absolute + " -->";
        }
        visited.add(absolute);
        String content = new String(raw, StandardCharsets.UTF_8);
        return expandIncludes(content, absolute.getParent(), boundary, depth, visited);
    }

    /** 逐行扫描：独占行的 @include 展开为引用文件内容，其余保持原文（F2）。 */
    private String expandIncludes(String content, Path baseDir, Path boundary,
                                  int depth, Set<Path> visited) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            Matcher m = INCLUDE_LINE.matcher(line.strip());
            if (!m.matches()) {
                out.append(line).append('\n');
                continue;
            }
            String relPath = m.group(1).strip();
            try {
                out.append(loadFile(baseDir.resolve(relPath), boundary, depth + 1, visited)).append('\n');
            } catch (IOException e) {
                out.append("<!-- @include 读取失败，已跳过: ").append(relPath).append(" -->\n");
            }
        }
        return out.toString();
    }

    /** 前 512 字节含 0x00 视为二进制（F6）。 */
    private static boolean isBinary(byte[] raw) {
        int n = Math.min(raw.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < n; i++) {
            if (raw[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** 供测试：构造时注入 userHome（隔离真实家目录）。 */
    static Loader withUserHome(Path projectRoot, Path userHome) {
        return new Loader(projectRoot, userHome);
    }

    private Loader(Path projectRoot, Path userHome) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.userHome = userHome.toAbsolutePath().normalize();
    }
}
