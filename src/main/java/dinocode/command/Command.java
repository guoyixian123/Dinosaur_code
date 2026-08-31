package dinocode.command;

import java.util.List;

/**
 * 斜杠命令元数据（ch10 F1/F8）：名字、别名、描述、执行类型的统一形态。
 *
 * @param name        不带 "/" 前缀、全小写、全局唯一
 * @param aliases     不带 "/" 前缀、全小写、全局唯一（含 name）
 * @param description 一句中文描述（/help 与补全菜单共用）
 * @param kind        执行类型：LOCAL 只输出 / UI 改状态 / PROMPT 注入消息触发回合
 * @param hidden      /help 与补全菜单都不显示，但 dispatcher 仍可命中（Skill 预留，F28）
 * @param handler     处理函数
 */
public record Command(
        String name,
        List<String> aliases,
        String description,
        Kind kind,
        boolean hidden,
        Handler handler) {

    public Command {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    /** 便捷工厂：无别名、非 hidden。 */
    public static Command of(String name, String description, Kind kind, Handler handler) {
        return new Command(name, List.of(), description, kind, false, handler);
    }
}
