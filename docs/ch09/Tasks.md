# 项目记忆与会话持久化 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 修改 | `src/main/java/com/dinocode/compact/SessionContext.java` | session ID 格式变更、新增 `sessionDir`、`open`、`parseSessionTime` |
| 修改 | `src/main/java/com/dinocode/conversation/Conversation.java` | 回调字段、`fromMessages`、append/replace 末尾触发 |
| 修改 | `src/test/java/dev/dinocode/conversation/ConversationTest.java` | 回调测试 |
| 修改 | `src/main/java/com/dinocode/prompt/Prompt.java` | `buildSystemPrompt` 签名变更 |
| 修改 | `src/main/java/com/dinocode/prompt/Modules.java` | `optionalModules` 接受参数 |
| 修改 | `src/test/java/dev/dinocode/prompt/PromptTest.java` | 新签名测试 |
| 新建 | `src/main/java/com/dinocode/instructions/Loader.java` | Loader 类型、三层加载、@include 展开 |
| 新建 | `src/test/java/dev/dinocode/instructions/LoaderTest.java` | @include 深度/环路/逃逸/缺失文件测试 |
| 新建 | `src/main/java/com/dinocode/session/Entry.java` | JSONL 行 record |
| 新建 | `src/main/java/com/dinocode/session/Writer.java` | Writer、create/open、append |
| 新建 | `src/main/java/com/dinocode/session/SessionInfo.java` | record SessionInfo |
| 新建 | `src/main/java/com/dinocode/session/SessionList.java` | listSessions |
| 新建 | `src/main/java/com/dinocode/session/SessionLoader.java` | load、坏行跳过、孤立截断 |
| 新建 | `src/main/java/com/dinocode/session/SessionCleaner.java` | cleanExpired |
| 新建 | `src/test/java/dev/dinocode/session/SessionTest.java` | JSONL 读写、列表、恢复、清理测试 |
| 新建 | `src/main/java/com/dinocode/memory/NoteType.java` | enum |
| 新建 | `src/main/java/com/dinocode/memory/Note.java` | record |
| 新建 | `src/main/java/com/dinocode/memory/UpdateAction.java` | record |
| 新建 | `src/main/java/com/dinocode/memory/Store.java` | 笔记文件 CRUD、索引读写 |
| 新建 | `src/main/java/com/dinocode/memory/Manager.java` | loadIndex、updateAsync |
| 新建 | `src/main/java/com/dinocode/memory/PromptTemplates.java` | 记忆更新 prompt 模板（中文） |
| 新建 | `src/test/java/dev/dinocode/memory/MemoryTest.java` | 索引加载、操作执行、截断测试 |
| 修改 | `src/main/java/com/dinocode/agent/Agent.java` | `run` 末尾触发记忆更新 |
| 修改 | `src/main/java/com/dinocode/agent/SessionRuntime.java` | `turnCount`、关联 memMgr |
| 修改 | `src/main/java/com/dinocode/tui/Commands.java` | /resume 命令注册 |
| 新建 | `src/main/java/com/dinocode/tui/ResumeStyles.java + MarkdownRenderer.java` | RESUMING、会话列表项、updateResuming |
| 修改 | `src/main/java/com/dinocode/tui/dinocodeModel.java` | RESUMING 集成、字段新增 |
| 修改 | `src/main/java/com/dinocode/dinocode.java` | 启动流程串联 |
| 修改 | `.dinocode/config.yaml.example` | 配置示例补充说明 |

## T1: Session ID 格式变更

**文件：** `src/main/java/com/dinocode/compact/SessionContext.java`
**依赖：** 无
**步骤：**
1. 修改 `newSessionId()`：格式从 `<unix_ts>-<8hex>` 改为 `YYYYMMDD-HHMMSS-<4hex>`。使用 `DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())` 拼接 4 字符随机十六进制（`HexFormat.of().formatHex(SecureRandom).substring(0,4)`）
2. `SessionContext` 新增 `Path sessionDir` 字段，值为 `workspace.resolve(".dinocode/sessions/").resolve(sessionId)`
3. 修改 `create(workspace)`：先算 `sessionDir`，`spillDir` 改为 `sessionDir.resolve("tool-results")`
4. 新增 `open(Path workspace, String sessionId)`：不创建目录，只检查目录存在后填充字段
5. 新增 `parseSessionTime(String sessionId) throws DateTimeParseException`：从 ID 前 15 位解析 `YYYYMMDD-HHMMSS`，供清理和排序使用

**验证：** `./gradlew -q test -Dtest=SessionContextTest` 通过；新 session ID 形如 `20260601-143022-a1b2`

## T2: Conversation 回调机制

**文件：** `src/main/java/com/dinocode/conversation/Conversation.java`, `src/test/java/dev/dinocode/conversation/ConversationTest.java`
**依赖：** 无
**步骤：**
1. `ConversationManager` 类新增 `Consumer<Message> onAppend` 和 `Consumer<List<Message>> onReplace` 两个可空 final 字段
2. 新构造函数 `Conversation(Consumer<Message> onAppend, Consumer<List<Message>> onReplace)`；保留无参构造（内部委派 null, null）
3. 静态工厂 `fromMessages(List<Message> msgs, Consumer<Message> onAppend, Consumer<List<Message>> onReplace)`：用 `List.copyOf(msgs)` 深拷贝初始化 messages 列表，设置回调
4. 在 `addUser`、`addAssistant`、`addAssistantWithToolCallComplete`、`addToolResults` 释放锁之后调用 `onAppend.accept(msg)`（如果非 null）
5. 在 `replaceMessages` 释放锁之后调用 `onReplace.accept(msgs)`（如果非 null）
6. 补充测试：验证回调被正确触发、回调参数符合预期；验证无回调时行为与 ch08 完全一致

**验证：** `./gradlew -q test -Dtest=ConversationTest` 通过

## T3: 项目指令加载器

**文件：** `src/main/java/com/dinocode/instructions/Loader.java`, `src/test/java/dev/dinocode/instructions/LoaderTest.java`
**依赖：** 无
**步骤：**
1. 定义 `Loader` 类：`Path projectRoot`、`Path userHome`、`int maxDepth = 5`
2. 构造函数 `Loader(Path projectRoot)`：`userHome = Path.of(System.getProperty("user.home"))`
3. 实现 `load()`：按优先级扫描三个路径，每个调 `loadFile`，结果用 `"\n\n"` 拼接（`String.join`），跳过空段
4. 实现 `loadFile(Path file, Path boundary, int depth, Set<Path> visited)`：
   - 检查 `depth > maxDepth` → 返回深度警告注释
   - `Path absolute = file.toRealPath()`；检查 `visited.contains(absolute)` → 环路警告
   - 检查 `absolute.startsWith(boundary)` → 否则逃逸警告
   - `Files.readAllBytes`；检查前 512 字节包含 `0x00` → 二进制警告
   - 按 `\n` 拆行，匹配 `^@include (.+)$` 独占行 → 递归 `loadFile` 展开
   - 返回展开后的完整内容
5. 测试用例：三层加载优先级、@include 正常展开、5 层深度截断、环路检测、路径逃逸、缺失文件跳过、二进制文件跳过

**验证：** `./gradlew -q test -Dtest=LoaderTest` 通过

## T4: Session Writer

**文件：** `src/main/java/com/dinocode/session/Entry.java`, `src/main/java/com/dinocode/session/Writer.java`
**依赖：** T1（sessionDir 字段）
**步骤：**
1. 定义 `Entry` record，使用 Jackson 注解（`@JsonInclude(JsonInclude.Include.NON_NULL)`，字段名走 `@JsonProperty`：`tool_calls`、`tool_results`、`ts`、`model`）
2. `Writer.create(Path sessionDir)`：`Files.createDirectories(sessionDir)`；以 `StandardOpenOption.CREATE, APPEND, WRITE` 打开 `conversation.jsonl`，构造 `BufferedWriter` + 持有 `FileChannel` 用于 force(true)
3. `Writer.open(Path sessionDir)`：同 `create` 但不创建目录，直接追加打开
4. `append(Message msg, String model, boolean isFirst)`：构造 `Entry`，isFirst 时填充 `model` 字段，加锁 → `ObjectMapper.writeValueAsString` + `\n` → `write` → `flush` + `channel.force(true)` → 解锁
5. `writeCompactMarker()`：写入 `{"type":"compact","ts":...}\n`
6. `appendAll(List<Message> msgs)`：逐条调 `append`（model = null、isFirst = false）
7. `close()`：关闭 BufferedWriter 与 FileChannel；实现 `Closeable`

**验证：** `./gradlew shadowJar` 编译通过

## T5: 会话列表扫描

**文件：** `src/main/java/com/dinocode/session/SessionInfo.java`, `src/main/java/com/dinocode/session/SessionList.java`
**依赖：** T1（parseSessionTime）
**步骤：**
1. 定义 `SessionInfo` record（含 `id`、`title`、`modifiedAt`、`model`、`size`、`dir`）
2. 实现 `SessionList.list(Path sessionsDir)`：
   - `Files.list(sessionsDir)` 遍历子目录（`try-with-resources`）
   - 对每个目录：尝试 `SessionContext.parseSessionTime(dirName)` → 异常则跳过（旧格式）
   - 检查 `conversation.jsonl` 是否存在 → 不存在则跳过
   - 打开 JSONL，逐行读到第一条 `role == "user"` 的消息 → 提取 `content` 作为 `title`（截断到 50 字符 + `…`）
   - 从第一条消息的 `model` 字段提取 model
   - `Files.size` 取文件大小；`Files.getLastModifiedTime` 取 `modifiedAt`
   - 按 `modifiedAt` 倒序 `Comparator.comparing(SessionInfo::modifiedAt).reversed()` 排列返回

**验证：** `./gradlew shadowJar` 编译通过

## T6: 会话加载恢复

**文件：** `src/main/java/com/dinocode/session/SessionLoader.java`
**依赖：** T4（Entry）
**步骤：**
1. 实现 `SessionLoader.load(Path sessionDir)`：
   - 用 `Files.newBufferedReader` 逐行读 `conversation.jsonl`，`ObjectMapper.readValue` 解析为 `Entry`
   - JSON 解析失败的行跳过（坏行容错）
   - 记录最后一个 `"compact".equals(entry.type())` 标记的行号
   - 从最后 compact 标记之后开始构建 `List<Message>`
   - 扫描结尾：如果最后一条是 assistant 且 `toolCalls` 非空，但后面没有 tool 消息 → 截断该条
   - 返回 messages
2. 提取 `truncateOrphanedToolCallComplete(List<Message> msgs)` 为独立静态方法便于测试

**验证：** `./gradlew shadowJar` 编译通过

## T7: 会话过期清理

**文件：** `src/main/java/com/dinocode/session/SessionCleaner.java`
**依赖：** T1（parseSessionTime）
**步骤：**
1. 实现 `SessionCleaner.cleanExpired(Path sessionsDir, Duration maxAge)`：
   - `Files.list(sessionsDir)` 遍历
   - `SessionContext.parseSessionTime(dirName)` → 抛异常则跳过
   - `Duration.between(parsedTime.atZone(ZoneId.systemDefault()).toInstant(), Instant.now())` > maxAge → 递归 `Files.walk` + `Files.delete`（或 `org.apache.commons.io.FileUtils.deleteDirectory`）
   - 单个删除失败 `Logger.warning` 后继续

**验证：** `./gradlew shadowJar` 编译通过

## T8: Session 包测试

**文件：** `src/test/java/dev/dinocode/session/SessionTest.java`
**依赖：** T4, T5, T6, T7
**步骤：**
1. `appendAndRead`：写入 3 条消息 → 逐行读回验证 JSON 结构
2. `compactMarker`：写入消息 → compact 标记 → 新消息 → `SessionLoader.load` 只返回 compact 后的
3. `loadBadLineSkip`：插入坏行 → 被跳过，其余正常
4. `loadOrphanedToolCallComplete`：末尾是带 tool_calls 的 assistant → 被截断
5. `listSessions`：创建 3 个 session 目录 → 列表返回 3 项，按时间倒序
6. `listSkipsOldFormat`：混合新旧格式目录 → 只返回新格式
7. `cleanExpired`：创建一个 31 天前和一个 1 天前的目录 → 只删 31 天前的

**验证：** `./gradlew -q test -Dtest=SessionTest` 通过

## T9: 笔记类型与存储

**文件：** `src/main/java/com/dinocode/memory/NoteType.java`, `Note.java`, `UpdateAction.java`, `Store.java`
**依赖：** 无
**步骤：**
1. `NoteType` enum：包含四个常量；`wire()` 返回 snake_case 名；`fromWire(String)` 反查
2. `Note`、`UpdateAction` record：Jackson 注解映射 `level`/`type`/`filename`/`slug` 等字段
3. `Store`：
   - 构造接受 `Path dir`
   - `ensureDir()`：`Files.createDirectories(dir)`
   - `loadIndex()`：读取 `MEMORY.md`；`NoSuchFileException` → 返回空字符串
   - `apply(List<UpdateAction> actions)`：
     - create：写 frontmatter + content 到 `<type>_<slug>.md`，在 MEMORY.md 追加一行
     - update：重写文件内容和 frontmatter，更新 MEMORY.md 对应行
     - delete：删除文件，移除 MEMORY.md 对应行
   - 用 `ReentrantLock` 保护文件写

**验证：** `./gradlew shadowJar` 编译通过

## T10: 记忆管理器

**文件：** `src/main/java/com/dinocode/memory/Manager.java`, `PromptTemplates.java`
**依赖：** T9
**步骤：**
1. `PromptTemplates`：定义记忆更新的系统提示常量（中文），包含规则说明和 JSON 输出格式
2. `Manager`：
   - 构造 `Manager(Path projectDir, Path userDir, LlmClient client, String model)`
   - `loadIndex()`：合并项目级和用户级索引（项目级在前），截断到 25KB 并附 `(index truncated)`
   - `setProvider(Provider, String model)`：延迟设置（启动时 provider 未选定）
   - `updateAsync(List<Message> recentMsgs)`：
     - `Thread.startVirtualThread(() -> { ... })`
     - 内部 `updateLock.lock()`（防并发更新）
     - 构造记忆更新请求：系统提示 + 最近消息 + 现有索引
     - 调 `provider.stream(messages)`（不传工具）订阅 `BlockingQueue`
     - 收集完整回复，`ObjectMapper.readValue(reply, new TypeReference<List<UpdateAction>>(){})` 解析 JSON 数组
     - 按 `level` 分发到 `projectStore.apply` / `userStore.apply`
     - 失败 `Logger.warning`，不上抛

**验证：** `./gradlew shadowJar` 编译通过

## T11: Memory 包测试

**文件：** `src/test/java/dev/dinocode/memory/MemoryTest.java`
**依赖：** T9, T10
**步骤：**
1. `storeCreateNote`：`apply` create → 文件存在、frontmatter 正确、MEMORY.md 有对应行
2. `storeUpdateNote`：`apply` update → 文件内容更新、MEMORY.md 对应行更新
3. `storeDeleteNote`：`apply` delete → 文件不存在、MEMORY.md 对应行消失
4. `managerLoadIndex`：两级各有索引 → 合并返回，项目级在前
5. `managerLoadIndexTruncate`：构造超 25KB 索引 → 截断 + truncated 标注
6. `managerUpdateAsyncParsesResponse`：mock provider 返回 JSON → 笔记文件被创建（用 `CountDownLatch` 等待 virtual thread 结束）

**验证：** `./gradlew -q test -Dtest=MemoryTest` 通过

## T12: buildSystemPrompt 参数化

**文件：** `src/main/java/com/dinocode/prompt/Prompt.java`, `Modules.java`, `src/test/java/dev/dinocode/prompt/PromptTest.java`
**依赖：** 无
**步骤：**
1. `Modules.optionalModules(String instructions, String memory)`：用参数填充对应 `Module.content`
2. `Prompt.buildSystemPrompt(String instructions, String memory)`：传参给 `optionalModules`
3. 更新所有调用方（`Agent.streamOnce` 等），传入对应参数（来自 Agent 字段）
4. 更新测试：验证非空参数时模块出现在系统提示中，空参数时模块被跳过

**验证：** `./gradlew -q test -Dtest=PromptTest` 通过

## T13: /resume 命令注册

**文件：** `src/main/java/com/dinocode/tui/Commands.java`
**依赖：** 无
**步骤：**
1. 在 `BUILTIN_COMMANDS` Map 中注册 `"/resume"` → `Commands::handleResume`
2. `handleResume(dinocodeModel app)`：检查 `app.state() == SessionState.IDLE`，调用 `app.beginResume()`

**验证：** `./gradlew shadowJar` 编译通过

## T14: 会话列表 UI

**文件：** `src/main/java/com/dinocode/tui/ResumeStyles.java + MarkdownRenderer.java`, `dinocodeModel.java`
**依赖：** T5（SessionList）, T13
**步骤：**
1. `dinocodeModel`：
   - `SessionState` enum 新增 `RESUMING`
   - 新增字段：`Writer writer`、`Manager memMgr`、`String instructionText`、`String memoryText`、`Path sessionsDir`
   - 构造扩展：接收 writer、memMgr、instructionText、memoryText
   - 主事件循环中 `RESUMING` 分支分发到 `ResumeView.update`
2. `ResumeView`：
   - 用 JLine/tui.tea `ActionListBox` 渲染会话列表项；每项 `Label` 文案 = `"<title>  ·  <relative>  ·  <model>  ·  <size>"`
   - `beginResume()`：调 `SessionList.list` → 填充 `ActionListBox` → `state = RESUMING`
   - 按键处理：
     - 字符键 → 累积搜索串，重新过滤 `ActionListBox`
     - Enter → 取选中 `SessionInfo` → 执行恢复流程 → `state = IDLE`
     - Esc → `state = IDLE`
   - 恢复流程 `doResumeSession(SessionInfo info)`：
     - `SessionLoader.load` → 截断孤立 → 估算 token → 超限则压缩
     - 检查时间跨度 → 超 6h 追加提醒
     - `Conversation.fromMessages(...)` → 新 conv
     - `SessionContext.open(root, info.id())` → 新 sesCtx
     - `Writer.open(info.dir())` → 新 writer
     - 替换 dinocodeModel 的 conv、writer、sesCtx、runtime.session
     - scrollback 追加系统消息

**验证：** `./gradlew shadowJar` 编译通过

## T15: Agent 记忆更新触发

**文件：** `src/main/java/com/dinocode/agent/Agent.java`, `SessionRuntime.java`
**依赖：** T10（Manager）
**步骤：**
1. `SessionRuntime`：新增 `long turnCount`；`Agent.Builder` 新增 `memoryManager(Manager)`、`instructionText(String)`、`memoryText(String)`
2. `Agent` 新增字段：`Manager memMgr`、`String instructionText`、`String memoryText`
3. `Agent.run` 循环的 Done 分支（模型回复无工具调用），在 `conv.addAssistant(text)` 后：
   - 提取最近一轮消息（从最后一条 user 到当前 assistant）
   - 递增 `runtime.turnCount`，满足任一条件时调 `memMgr.updateAsync(recent)`：
     - `runtime.turnCount() % 5 == 0`
     - `hasMemorySignal(recent)` 检测到"记住/记忆/别忘/remember/memo"关键词
4. `Agent.streamOnce` 中 `Prompt.buildSystemPrompt` 调用改为传入 `instructionText` 和 `memoryText`

**验证：** `./gradlew shadowJar` 编译通过

## T16: Main 启动流程串联

**文件：** `src/main/java/com/dinocode/dinocode.java`
**依赖：** T1, T2, T3, T4, T10, T12, T14, T15
**步骤：**
1. 在 `ConfigLoader.load` 之后、`ToolToolRegistry.createDefault()` 之前插入：
   - `var instructionText = new Loader(root).load();`
   - `var memMgr = new Manager(projectMemDir, userMemDir, null, "");`
   - `var memoryText = memMgr.loadIndex();`
2. 在 `SessionContext.create` 之后：
   - `var writer = Writer.create(sesCtx.sessionDir);`
3. 在 `PermissionEngine.create` 之后：
   - `Thread.startVirtualThread(() -> SessionCleaner.cleanExpired(sessionsDir, Duration.ofDays(30)));`
4. 修改 `new Conversation()` → `new Conversation(writer::onAppend, writer::onReplace)`
   其中 `writer.onAppend(Message)` / `writer.onReplace(List<Message>)` 是 Writer 上的实例方法，内部委派 `append`/`writeCompactMarker` + `appendAll`
5. 修改 `new dinocodeModel(...)` 调用：传入 writer、memMgr、instructionText、memoryText
6. 在 dinocodeModel 的 provider 选定回调中：调 `memMgr.setProvider(provider, model)`

**验证：** `./gradlew shadowJar` 编译通过；`./gradlew -q spotless:check` 无错

## T17: 配置示例更新

**文件：** `.dinocode/config.yaml.example`
**依赖：** 无
**步骤：**
1. 在配置示例中添加注释，说明 dinocode.md 的加载路径和优先级
2. 说明 `memory/` 和 `sessions/` 目录的用途

**验证：** 目视检查示例文件内容完整

## 执行顺序

```
T1（Session ID）─┐
T2（Conv 回调）──┤
T3（指令加载）──┤
T9（笔记存储）──┤── 独立基础模块，可并行
T12（Prompt 参数化）─┤
T13（/resume 注册）──┤
T17（配置示例）──────┘

T4（Session Writer）── 依赖 T1
T5（会话列表）──────── 依赖 T1
T6（会话加载）──────── 依赖 T4
T7（会话清理）──────── 依赖 T1
T8（Session 测试）──── 依赖 T4,T5,T6,T7

T10（记忆管理器）──── 依赖 T9
T11（Memory 测试）─── 依赖 T9,T10

T14（会话列表 UI）─── 依赖 T5,T13
T15（Agent 记忆触发）── 依赖 T10,T12
T16（Main 串联）────── 依赖 T1,T2,T3,T4,T10,T12,T14,T15
```