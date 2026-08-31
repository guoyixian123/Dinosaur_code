# 项目记忆与会话持久化 Plan

## 架构概览

本章新增三个独立包，加上对现有包的窄幅修改：

| 新包 | 职责 |
|------|------|
| `com.dinocode.instructions` | 三层 dinocode.md 加载 + @include 展开 |
| `com.dinocode.session` | JSONL 会话写入、列表扫描、加载恢复、过期清理 |
| `com.dinocode.memory` | 笔记 CRUD、索引管理、异步 LLM 更新 |

| 已有包 | 改动 |
|--------|------|
| `com.dinocode.prompt` | `buildSystemPrompt` 接受 instructions/memory 参数 |
| `com.dinocode.conversation` | 新增 onAppend/onReplace 回调 |
| `com.dinocode.compact.SessionContext` | session ID 格式改为 YYYYMMDD-HHMMSS-xxxx；加 `sessionDir` 字段 |
| `com.dinocode.agent` | 每 5 轮 `run` 结束后触发记忆更新 |
| `com.dinocode.tui` | 新增 /resume 命令和 `SessionState.RESUMING` 状态 |
| `com.dinocode.dinocode` | 启动流程串联指令加载、记忆初始化、会话清理 |

## 核心数据结构

### instructions 包

```java
package com.dinocode.instructions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

// Loader 负责三层 dinocode.md 的加载和 @include 展开。
public final class Loader {
    private final Path projectRoot;
    private final Path userHome;
    private final int maxDepth; // 固定 5

    public Loader(Path projectRoot) { /* userHome = System.getProperty("user.home") */ }

    // load 按优先级加载三层指令文件，返回拼接后的完整指令文本。
    // 加载失败的层静默跳过，全部为空返回空字符串。
    public String load() throws IOException { ... }

    // loadFile 加载单个文件，处理 @include 展开。
    // boundary 是路径逃逸检测的根边界。
    // depth 当前嵌套层数（从 1 开始），visited 环路检测集合。
    String loadFile(Path file, Path boundary, int depth, Set<Path> visited) throws IOException { ... }
}
```

### session 包

```java
package com.dinocode.session;

import com.dinocode.llm.Message;
import com.dinocode.llm.ToolCall;
import com.dinocode.llm.ToolResult;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

// Entry 是 JSONL 中一行的结构（Jackson 注解略；type/role 互斥，type=="compact" 表示标记行）。
public record Entry(
        String type,                  // "compact" 或 null
        String role,                  // "user"/"assistant"/"tool" 或 null
        String content,               // 可空
        List<ToolCall> toolCalls,     // 仅 assistant
        List<ToolResult> toolResults, // 仅 tool
        long ts,
        String model                  // 仅首条消息
) {}

// Writer 负责向 conversation.jsonl 追加写入。
public final class Writer implements Closeable {
    private final Path file;
    private final java.io.BufferedWriter out;
    private final java.io.FileDescriptor fd;
    private final ReentrantLock lock = new ReentrantLock();

    public static Writer create(Path sessionDir) throws IOException { ... }
    public static Writer open(Path sessionDir)   throws IOException { ... }

    public void append(Message msg, String model, boolean isFirst) throws IOException { ... }
    public void writeCompactMarker() throws IOException { ... }
    public void appendAll(List<Message> msgs) throws IOException { ... }

    @Override public void close() throws IOException { ... }
}

// SessionInfo 是会话列表中一项的摘要信息。
public record SessionInfo(
        String id,             // session ID（目录名）
        String title,          // 第一条 user 消息内容（截断）
        Instant modifiedAt,    // 最后修改时间
        String model,          // 模型标签
        long size,             // JSONL 文件大小
        Path dir               // 会话目录绝对路径
) {}

// listSessions 扫描 sessionsDir，返回按修改时间倒序排列的会话列表。
// 只返回包含 conversation.jsonl 且 ID 能解析为新格式的目录。
public final class SessionList {
    public static List<SessionInfo> list(Path sessionsDir) throws IOException { ... }
}

// loadSession 从 conversation.jsonl 恢复消息列表。
// 从最后一个 compact 标记之后加载，跳过坏行，截断孤立工具调用。
public final class SessionLoader {
    public static List<Message> load(Path sessionDir) throws IOException { ... }
    public static List<Message> truncateOrphanedToolCallComplete(List<Message> msgs) { ... }
}

// cleanExpired 删除超过 maxAge 的会话目录。
// 只处理新格式 ID 的目录，旧格式跳过。
public final class SessionCleaner {
    public static void cleanExpired(Path sessionsDir, java.time.Duration maxAge) { ... }
}
```

### memory 包

```java
package com.dinocode.memory;

import com.dinocode.llm.Message;
import com.dinocode.llm.Provider;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

// NoteType 笔记类型。
public enum NoteType {
    USER_PREFERENCE("user_preference"),
    CORRECTION_FEEDBACK("correction_feedback"),
    PROJECT_KNOWLEDGE("project_knowledge"),
    REFERENCE_MATERIAL("reference_material");

    private final String wire;
    NoteType(String wire) { this.wire = wire; }
    public String wire() { return wire; }
    public static NoteType fromWire(String s) { ... }
}

// Note 一条笔记的内存表示。
public record Note(
        NoteType type,
        String title,
        String slug,
        String content,
        String filename,
        Instant created,
        Instant updated
) {}

// UpdateAction LLM 返回的单条操作（Jackson 反序列化）。
public record UpdateAction(
        String action,   // "create"/"update"/"delete"
        String level,    // "project"/"user"
        String type,     // NoteType.wire（create 时必需）
        String title,    // 笔记标题
        String slug,     // 文件名 slug（create 时必需）
        String content,  // 笔记正文（create/update 时必需）
        String filename  // 已有文件名（update/delete 时必需）
) {}

// Store 管理单级（项目级或用户级）的笔记文件和索引。
public final class Store {
    private final Path dir; // .dinocode/memory/ 或 ~/.dinocode/memory/
    private final ReentrantLock lock = new ReentrantLock();

    public Store(Path dir) { ... }
    public void ensureDir() throws java.io.IOException { ... }
    public String loadIndex() throws java.io.IOException { ... } // 读 MEMORY.md
    public void apply(List<UpdateAction> actions) throws java.io.IOException { ... }
}

// Manager 编排项目级和用户级笔记的加载和更新。
public final class Manager {
    private final Store projectStore;
    private final Store userStore;
    private volatile Provider provider;
    private volatile String model;
    private final ReentrantLock updateLock = new ReentrantLock();

    public Manager(Path projectDir, Path userDir, LlmClient client, String model) { ... }
    public String loadIndex();                                  // 合并两级索引，截断到 25KB
    public void setProvider(LlmClient client, String model);   // 延迟设置
    public void updateAsync(List<Message> recentMsgs);          // 异步 LLM 调用，virtual thread
}
```

### conversation 包（修改）

```java
package com.dinocode.conversation;

import com.dinocode.llm.Message;
import java.util.List;
import java.util.function.Consumer;

public final class Conversation {
    private final java.util.ArrayList<Message> messages = new java.util.ArrayList<>();
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
    private final Consumer<Message> onAppend;        // 可空：消息追加回调
    private final Consumer<List<Message>> onReplace; // 可空：消息替换回调

    public Conversation() { this(null, null); }
    public Conversation(Consumer<Message> onAppend, Consumer<List<Message>> onReplace) { ... }

    // newFromMessages 从已有消息列表创建会话（恢复场景），可选回调。
    public static Conversation fromMessages(
            List<Message> msgs,
            Consumer<Message> onAppend,
            Consumer<List<Message>> onReplace) { ... }
    // 其他 addUser/addAssistant/... 接口保持不变，末尾触发回调
}
```

### compact 包（修改）

```java
package com.dinocode.compact;

import java.nio.file.Path;
import java.time.LocalDateTime;

public final class SessionContext {
    public final String sessionId;  // 形如 "20260601-143022-a1b2"
    public final Path sessionDir;   // <workspace>/.dinocode/sessions/<sessionId>
    public final Path spillDir;     // sessionDir.resolve("tool-results")

    public static SessionContext create(Path workspace) { ... }
    public static SessionContext open(Path workspace, String sessionId) throws java.io.IOException { ... }

    // newSessionId 改为 YYYYMMDD-HHMMSS-xxxx 格式。
    static String newSessionId() { ... }
    // parseSessionTime 从 ID 前 15 位解析 YYYYMMDD-HHMMSS，供清理和排序使用。
    public static LocalDateTime parseSessionTime(String sessionId) { ... }
}
```

### prompt 包（修改）

```java
package com.dinocode.prompt;

public final class Prompt {
    // buildSystemPrompt 组装完整系统提示。
    // instructions 非空时填入 custom-instructions 模块（priority 80）。
    // memory 非空时填入 long-term-memory 模块（priority 100）。
    public static String buildSystemPrompt(String instructions, String memory) { ... }
}
```

### agent 包（修改）

```java
package com.dinocode.agent;

public final class Agent {
    private final com.dinocode.memory.Manager memMgr; // 可空：记忆更新管理器
    private final String instructionText;            // 注入 system prompt
    private final String memoryText;                 // 注入 system prompt
    // ... 已有字段

    public static final class Builder {
        public Builder memoryManager(com.dinocode.memory.Manager m) { ... }
        public Builder instructionText(String s) { ... }
        public Builder memoryText(String s) { ... }
        public Agent build() { ... }
    }
}
```

## 模块交互

### 启动流程

```
dinocode.main()
  ├─ ConfigLoader.load(Path.of(".dinocode/config.yaml"))
  ├─ new Loader(projectRoot).load() → instructionText
  ├─ new Manager(projectMemDir, userMemDir, null, "") → memMgr
  │   （provider 未选定时先空，选定后 setProvider）
  ├─ memMgr.loadIndex() → memoryText
  ├─ SessionContext.create(root) → sesCtx       （新格式 session ID）
  ├─ Writer.create(sesCtx.sessionDir)   → writer
  ├─ Thread.startVirtualThread(() ->
  │       SessionCleaner.cleanExpired(sessionsDir, Duration.ofDays(30))) （后台清理）
  ├─ ToolToolRegistry.createDefault()
  ├─ McpManager.newManager() → mcpTools → registry.register(...)
  ├─ PermissionEngine.create()
  ├─ SessionRuntime.create(ctxWindow)
  │   └─ runtime.session = sesCtx
  └─ new dinocodeModel(providers, ..., writer, memMgr, instructionText, memoryText).run()
       └─ 选定 provider 后：
           ├─ memMgr.setProvider(provider, model)
           └─ Agent.builder()...memoryManager(memMgr).build()
```

### Agent Loop 与记忆更新

```
Agent.run(conv, mode) → BlockingQueue<AgentEvent>
  for (;;) {
    streamOnce → text / toolCalls
    if (无工具调用 / Done) {
      conv.addAssistant(text);  // → Writer.append (via onAppend)
      // 每 5 轮或检测到显式记忆请求时触发异步记忆更新
      if (memMgr != null) {
        runtime.turnCount++;
        var recent = extractRecentTurn(conv);
        if (runtime.turnCount % 5 == 0 || hasMemorySignal(recent)) {
            memMgr.updateAsync(recent); // 内部 Thread.startVirtualThread
        }
      }
      emit Done; return;
    }
    // 有工具调用：继续迭代
    ...
  }
```

### /resume 恢复流程

```
TUI: /resume → SessionState.RESUMING
  ├─ SessionList.list(sessionsDir) → items
  ├─ 显示 ActionListBox（上下选择 + 字符触发搜索过滤）
  ├─ Enter 选择：
  │   ├─ SessionLoader.load(selectedDir) → msgs
  │   ├─ 检查孤立工具调用 → 截断
  │   ├─ 估算 token → 超限则压缩
  │   ├─ 检查时间跨度 → 超 6h 追加提醒
  │   ├─ Conversation.fromMessages(msgs, onAppend, onReplace) → newConv
  │   ├─ SessionContext.open(root, selectedId) → newSesCtx
  │   ├─ Writer.open(selectedDir) → newWriter
  │   ├─ 替换 dinocodeModel 的 conv、writer、sesCtx、runtime.session
  │   ├─ 显示 "已恢复会话 <id>，共 N 条消息"
  │   └─ SessionState.IDLE
  └─ Esc → SessionState.IDLE（不变）
```

### JSONL 写入时序

```
用户输入 "hello"
  → conv.addUser("hello")
    → onAppend(new Message(Role.USER, "hello"))
      → writer.append(msg, model, /*isFirst=*/true)
        → {"role":"user","content":"hello","ts":1717200000,"model":"gpt-5.4-mini"}\n

Agent 回复 "hi!"
  → conv.addAssistant("hi!")
    → onAppend(new Message(Role.ASSISTANT, "hi!"))
      → writer.append(msg, model, /*isFirst=*/false)
        → {"role":"assistant","content":"hi!","ts":1717200005}\n

压缩触发
  → conv.replaceMessages(newMsgs)
    → onReplace(newMsgs)
      → writer.writeCompactMarker()
        → {"type":"compact","ts":1717201000}\n
      → writer.appendAll(newMsgs)
        → 逐条追加新消息
```

## 文件组织

```
dinocode/
├── build.gradle.kts
├── src/main/java/com/dinocode/
│   ├── dinocode.java                            — 启动流程串联
│   ├── instructions/
│   │   └── Loader.java                      — 三层加载、@include 展开
│   ├── session/
│   │   ├── Entry.java                       — record Entry
│   │   ├── Writer.java                      — Writer / create / open / append / writeCompactMarker
│   │   ├── SessionInfo.java                 — record SessionInfo
│   │   ├── SessionList.java                 — listSessions
│   │   ├── SessionLoader.java               — load、坏行跳过、孤立截断
│   │   └── SessionCleaner.java              — cleanExpired、ID 时间戳解析
│   ├── memory/
│   │   ├── NoteType.java                    — enum
│   │   ├── Note.java                        — record
│   │   ├── UpdateAction.java                — record
│   │   ├── Store.java                       — Store、笔记文件 CRUD、索引读写
│   │   ├── Manager.java                     — Manager、loadIndex、updateAsync
│   │   └── PromptTemplates.java             — 记忆更新 prompt 模板（中文）
│   ├── prompt/
│   │   ├── Prompt.java                      — buildSystemPrompt(instructions, memory)
│   │   └── Modules.java                     — optionalModules 改为接受参数
│   ├── conversation/
│   │   └── Conversation.java                — 回调字段、fromMessages
│   ├── compact/
│   │   └── SessionContext.java              — newSessionId 格式变更、sessionDir、open
│   ├── agent/
│   │   ├── Agent.java                       — 每 5 轮 run 末尾触发 memMgr.updateAsync
│   │   └── SessionRuntime.java              — turnCount、关联 memMgr
│   └── tui/
│       ├── Commands.java                    — /resume 注册
│       ├── ResumeStyles.java + MarkdownRenderer.java                  — RESUMING、会话列表项、updateResuming
│       └── dinocodeModel.java                      — RESUMING 集成、字段新增
├── src/test/java/dev/dinocode/
│   ├── instructions/LoaderTest.java
│   ├── session/SessionTest.java
│   ├── memory/MemoryTest.java
│   ├── prompt/PromptTest.java
│   └── conversation/ConversationTest.java
└── .dinocode/config.yaml
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 指令文件格式 | 手写 Markdown | 用户直接编辑，不需要特殊工具；与系统提示纯文本注入无缝衔接 |
| @include 深度限制 | 5 层 | 足够覆盖合理的模块化拆分，又不会因为误配无限递归 |
| 会话存储格式 | JSONL 追加写 | 追加快、崩溃安全（只丢最后一行）、无需维护索引文件 |
| JSON 库 | Jackson（`com.fasterxml.jackson.core:jackson-databind`） | 与 SDK 内部一致；`ObjectMapper.writeValueAsString` + `\n` 拼装 JSONL 简洁 |
| 压缩后 JSONL 处理 | compact 标记行 + 追加新消息 | 保持追加语义，恢复时从最后 compact 标记开始加载 |
| session ID 格式 | YYYYMMDD-HHMMSS-xxxx | 人类可读，可直接从 ID 解析时间戳用于过期清理和排序（`DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")`） |
| 记忆更新触发点 | 每 5 轮或检测到"记住"关键词时 | 定时提取控制频率；关键词检测保证显式请求不漏 |
| 记忆去重策略 | LLM 判断 | 语义级去重比机械字符串匹配更准确，且实现简单 |
| 记忆注入方式 | 索引注入系统提示 | 约 2-3K tokens 开销可控，模型通过索引感知全貌，详情按需读文件 |
| Conversation 回调 | 构造时注入 `Consumer<T>` | 最小侵入，不需要引入事件总线；未设置回调时零开销 |
| /resume 列表组件 | 复用 JLine/tui.tea `ActionListBox` / `RadioBoxList` | 与已有 provider 选择列表一致的交互模式，减少代码和认知负担 |
| 后台清理执行模型 | virtual thread (`Thread.startVirtualThread`) | 与项目其他后台任务一致；阻塞 I/O 不挤占主线程 |
| 并发保护 | `ReentrantLock`（Writer / Memory Manager / Store） | JDK 内置；fair 模式可控；与 virtual thread 兼容 |
| 文件 sync | `FileChannel.force(true)` 或 `FileDescriptor.sync()` | 满足崩溃安全要求；每条 append 后立即刷盘 |
| 记忆 provider | 复用主会话 provider | 简单直接，不引入额外配置；未来可扩展为配置专用 provider |