package dinocode.teams;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 名字注册表（ch15 F20/T11）：全局单例 name → agentId，支持反向寻址。
 */
public final class AgentNameRegistry {

    private static final AgentNameRegistry INSTANCE = new AgentNameRegistry();

    public static AgentNameRegistry getInstance() {
        return INSTANCE;
    }

    private final Map<String, String> nameToId = new LinkedHashMap<>();

    private AgentNameRegistry() {
    }

    public synchronized void register(String name, String agentId) {
        nameToId.put(name, agentId);
    }

    /** name 或 agentId 皆可寻址；都不命中返回 null（F20）。 */
    public synchronized String resolve(String nameOrId) {
        if (nameOrId == null) {
            return null;
        }
        String id = nameToId.get(nameOrId);
        if (id != null) {
            return id;
        }
        // 反向：传入的已是 agentId
        if (nameToId.containsValue(nameOrId)) {
            return nameOrId;
        }
        return null;
    }

    public synchronized void unregister(String name) {
        nameToId.remove(name);
    }

    /** 副本防外部修改（T11）。 */
    public synchronized Map<String, String> listAll() {
        return new LinkedHashMap<>(nameToId);
    }
}
