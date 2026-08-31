package dinocode.permission;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 危险命令黑名单（ch06 F1/N1）。
 *
 * <p><b>启发式防御而非完备保证</b>：不追求穷尽所有危险命令；防御纵深由沙箱、
 * 规则引擎与人在回路补足。
 *
 * <p><b>不可配置放开</b>：正则集为编译期常量，无任何加载入口——不存在任何
 * 配置项、规则或权限模式（含 bypassPermissions）能绕过本层。
 */
final class Blacklist {

    private static final List<Pattern> PATTERNS = List.of(
            // 递归强删根/家目录：rm -rf / 、rm -fr ~、rm -r --force $HOME 等
            Pattern.compile("\\brm\\s+(-[a-zA-Z]*[rRf][a-zA-Z]*\\s+)+(/|~|\\$HOME|/\\*|\\\"/\\\")"),
            // 写块设备 / 磁盘：dd if=... of=/dev/sda
            Pattern.compile("\\bdd\\b[^|]*\\bof=/dev/(sd|hd|nvme|disk|mapper)"),
            // fork 炸弹：:(){ :|:& };:
            Pattern.compile(":\\(\\)\\s*\\{[^}]*\\|[^}]*&[^}]*\\}"),
            // 格式化文件系统
            Pattern.compile("\\bmkfs(\\.\\w+)?\\b"),
            // 重定向覆盖磁盘设备：> /dev/sda
            Pattern.compile(">\\s*/dev/(sd[a-z]|hd[a-z]|nvme\\d|disk)"),
            // 递归放开根目录权限
            Pattern.compile("\\bchmod\\s+-[a-zA-Z]*R[a-zA-Z]*\\s+0?777\\s+(/|~)"),
            // 清空根下所有内容
            Pattern.compile("\\b(find|mv|cp)\\b[^;|&]*\\s(/|~/)\\s*($|[;|&])"));

    private Blacklist() {
    }

    /** 命令串命中任一高危模式即真（find 语义，子串匹配）。 */
    static boolean hits(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        for (Pattern p : PATTERNS) {
            if (p.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }

    /** 命中的正则串（用于被拒原因展示）。 */
    static String describe(String command) {
        if (command == null) {
            return "";
        }
        for (Pattern p : PATTERNS) {
            if (p.matcher(command).find()) {
                return p.pattern();
            }
        }
        return "";
    }
}
