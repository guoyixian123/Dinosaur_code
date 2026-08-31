package dinocode.compact.state;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 会话生命周期信息（ch08 F34/F35）：sessionId 进程启动时一次性生成、不持久化；
 * 落盘目录固定为 <workspace>/.dino/sessions/&lt;session_id&gt;/tool-results/。
 */
public record SessionContext(String sessionId, Path spillDir) {

    /** 生成 sessionId（&lt;unix_ts&gt;-&lt;8位hex&gt;）并按需创建落盘目录；已存在不报错。 */
    public static SessionContext create(Path workspace) {
        String sessionId = newSessionId();
        Path spillDir = workspace.toAbsolutePath()
                .resolve(".dino").resolve("sessions").resolve(sessionId).resolve("tool-results");
        try {
            Files.createDirectories(spillDir);
        } catch (IOException e) {
            // 目录创建失败：落盘不可用，但会话仍可运行（N6 错误隔离——落盘失败会降级不替换）
            throw new UncheckedIOException(e);
        }
        return new SessionContext(sessionId, spillDir);
    }

    private static String newSessionId() {
        byte[] bytes = new byte[4];
        new SecureRandom().nextBytes(bytes);
        return Instant.now().getEpochSecond() + "-" + HexFormat.of().formatHex(bytes);
    }
}
