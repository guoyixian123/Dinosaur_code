package dinocode.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 命令注册中心（ch10 F1/F2/F25）：名字+别名统一索引、启动期冲突检测（F2/N4 失败快）、
 * 可见命令字典序列表（/help 与补全菜单共用信源，N7）、按主名前缀匹配（补全菜单，F25）。
 */
public final class CommandRegistry {

    /** 主名 + 别名都映射到同一 Command，key 已小写。 */
    private final Map<String, Command> byName = new HashMap<>();
    /** 按名字典序排序的可见命令（排除 hidden），给 /help 与补全菜单使用。 */
    private final List<Command> visible = new ArrayList<>();

    /**
     * 注册一条命令；名字或别名与已注册键冲突立即抛 {@link IllegalStateException}
     * （含冲突键名，N4）——进程启动期调用，失败即终止启动（F2/AC16）。
     */
    public void register(Command c) {
        List<String> keys = new ArrayList<>(c.aliases().size() + 1);
        keys.add(c.name());
        keys.addAll(c.aliases());
        for (String key : keys) {
            String normalized = key.toLowerCase();
            if (!normalized.equals(key)) {
                throw new IllegalStateException("命令名/别名必须全小写: " + key);
            }
            if (byName.containsKey(normalized)) {
                throw new IllegalStateException("命令名/别名冲突: " + normalized);
            }
        }
        for (String key : keys) {
            byName.put(key.toLowerCase(), c);
        }
        if (!c.hidden()) {
            visible.add(c);
            visible.sort(Comparator.comparing(Command::name));
        }
    }

    /** 按名字或别名查找（大小写不敏感，F4）。 */
    public Optional<Command> lookup(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(name.toLowerCase()));
    }

    /** 可见命令副本（字典序、排除 hidden）——/help 与补全菜单的唯一信源（N7/F28）。 */
    public List<Command> visible() {
        return List.copyOf(visible);
    }

    /** 按主名前缀匹配可见命令（补全菜单数据源，F24/F25）；prefix 可带 "/"，空串返回全部。 */
    public List<Command> prefixMatch(String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase().strip();
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        List<Command> out = new ArrayList<>();
        for (Command c : visible) {
            if (c.name().startsWith(p)) {
                out.add(c);
            }
        }
        return out;
    }
}
