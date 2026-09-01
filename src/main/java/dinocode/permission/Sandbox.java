package dinocode.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 路径沙箱（ch06 F2/N2）：把文件类工具的读写限定在项目根目录内。
 *
 * <p>核心顺序固定——<b>先解析符号链接、再做前缀判断</b>，防软链接逃逸；
 * 对尚不存在的目标（新建文件、含未创建中间目录）解析其最近已存在祖先目录后再判断。
 * {@code Path.startsWith} 按段比对，天然避免把 {@code <root>foo} 误当 {@code <root>/foo}。
 */
final class Sandbox {

    private Sandbox() {
    }

    /** 项目根解析为绝对 + 真实路径。 */
    static Path resolveRoot(Path root) throws IOException {
        return root.toAbsolutePath().toRealPath();
    }

    /** 解析失败时回退为绝对路径（测试/降级路径用）。 */
    static Path rootUnchecked(Path root) {
        try {
            return resolveRoot(root);
        } catch (IOException e) {
            return root.toAbsolutePath();
        }
    }

    /** 路径是否落在项目根内；空路径视为 root 本身。 */
    static boolean ok(Path root, String path) {
        if (path == null || path.isEmpty()) {
            return true;
        }
        Path abs = Path.of(path);
        if (!abs.isAbsolute()) {
            abs = root.resolve(abs);
        }
        try {
            Path resolved = evalSymlinksOrAncestor(abs);
            return resolved.equals(root) || resolved.startsWith(root);
        } catch (IOException e) {
            return false; // 解析失败按最严处理（N7）
        }
    }

    /**
     * 存在的目标 {@code toRealPath()}；不存在则取最近<b>已存在祖先</b>目录
     * {@code toRealPath()} 后拼回剩余段（覆盖新建文件与未创建中间目录）。
     *
     * <p>符号链接（含悬空链接）优先解引用一层再重走判定——{@code Files.exists(NOFOLLOW)}
     * 对悬空链接返回 true 但 {@code toRealPath()} 抛异常，且不解析会让「父目录合法、
     * 链接指向沙箱外」的逃逸漏判（N2）。递归有界：每次剥离一层链接。
     */
    static Path evalSymlinksOrAncestor(Path abs) throws IOException {
        if (Files.isSymbolicLink(abs)) {
            Path parent = abs.getParent().toRealPath();
            Path target = Files.readSymbolicLink(abs);
            Path deref = target.isAbsolute() ? target : parent.resolve(target);
            return evalSymlinksOrAncestor(deref);
        }
        if (Files.exists(abs)) {
            return abs.toRealPath();
        }
        Path parent = abs.getParent();
        Path tail = abs.getFileName();
        while (parent != null && !Files.exists(parent)) {
            tail = parent.getFileName().resolve(tail == null ? Path.of(".") : tail);
            parent = parent.getParent();
        }
        if (parent == null) {
            return abs.toRealPath(); // 整条链都不存在，让 toRealPath 抛异常由调用方兜底
        }
        return parent.toRealPath().resolve(tail == null ? Path.of(".") : tail);
    }
}
