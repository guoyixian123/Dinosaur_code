package dinocode.session.archive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dinocode.core.ToolCall;
import dinocode.core.ToolResult;

import java.util.List;

/**
 * JSONL 单行结构（ch09 F11）。type 与 role 互斥：type=="compact" 为压缩标记行（F12）。
 * isCompact 是派生方法，不参与序列化（加 @JsonIgnore）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Entry(
        String type,
        String role,
        String content,
        List<ToolCall> toolCalls,
        List<ToolResult> toolResults,
        long ts,
        String model) {

    public static final String TYPE_COMPACT = "compact";

    public Entry {
        toolCalls = toolCalls == null ? List.of() : toolCalls;
        toolResults = toolResults == null ? List.of() : toolResults;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isCompact() {
        return TYPE_COMPACT.equals(type);
    }
}
