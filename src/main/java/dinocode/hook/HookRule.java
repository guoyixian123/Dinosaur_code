package dinocode.hook;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一条 Hook 规则（ch12 F7/F8）：事件 + 条件 + 动作三要素 + 执行控制。
 *
 * @param name     必填；日志、only_once 跟踪、冲突检测（F7）
 * @param event    11 选 1
 * @param condition 可省略（null = 无条件触发，G3）
 * @param action   动作对象
 * @param onlyOnce 会话内只跑一次（F27）
 * @param async    后台异步执行（拦截事件不允许，F28）
 * @param timeout  命令/HTTP 最大执行时长（null = 默认 30s）
 * @param source   来源文件路径（/hooks 展示，AC12）
 */
public record HookRule(
        String name,
        Event event,
        Condition condition,
        Action action,
        boolean onlyOnce,
        boolean async,
        Duration timeout,
        String source) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    public Duration effectiveTimeout() {
        return timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    /** 事件 payload：通用字段 + 特化字段；JSON 序列化按 key 字典序（N6）。 */
    public static final class Payload {

        private final Map<String, Object> data;

        public Payload(Map<String, Object> data) {
            this.data = data == null ? Map.of() : data;
        }

        /** 嵌套字段路径取值（F13）：路径不存在返回空串，不报错。 */
        public String getByPath(String path) {
            Object cur = data;
            for (String part : (path == null ? "" : path).split("\\.")) {
                if (cur instanceof Map<?, ?> map) {
                    cur = map.get(part);
                } else {
                    return "";
                }
                if (cur == null) {
                    return "";
                }
            }
            return cur instanceof String s ? s : String.valueOf(cur);
        }

        /** key 字典序的 JSON 串（N6，方便脚本 grep）。 */
        public String toSortedJson() {
            Map<String, Object> sorted = new TreeMap<>();
            data.forEach((k, v) -> {
                if (v instanceof Map<?, ?> nested) {
                    Map<String, Object> n = new TreeMap<>();
                    nested.forEach((nk, nv) -> n.put(String.valueOf(nk), nv));
                    sorted.put(k, n);
                } else {
                    sorted.put(k, v);
                }
            });
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(sorted);
            } catch (Exception e) {
                return "{}";
            }
        }

        public Map<String, Object> data() {
            return data;
        }
    }
}
