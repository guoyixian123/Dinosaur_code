package dino.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 消息角色。序列化为小写字符串（会话文件与两家 API 均用小写）。
 */
public enum Role {
    USER("user"),
    ASSISTANT("assistant");

    private final String wire;

    Role(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static Role fromWire(String wire) {
        for (Role r : values()) {
            if (r.wire.equals(wire)) {
                return r;
            }
        }
        throw new IllegalArgumentException("未知角色: " + wire);
    }
}
