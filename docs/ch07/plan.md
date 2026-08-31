# MCP 客户端 Plan

> 技术栈：Java 21（LTS；virtual thread + `BlockingQueue`）+ Gradle；使用 **官方 Java SDK** `io.modelcontextprotocol.sdk:mcp` 承载协议层（JSON-RPC 编解码、initialize 握手、stdio 与 Streamable HTTP 传输）。本章新增 **`com.Dino Code.mcp` 包** 与 `Dino Code` 装配,**不改 tool / agent / tui / permission / llm / config / conversation / prompt**。

## 架构概览

- **`com.Dino Code.mcp` 包（新增）**：承载 MCP 客户端的全部职责——配置加载与两层合并、`${VAR}` 展开、字段校验、调用 SDK 建立 stdio / HTTP 会话、把远端工具适配成内置 `Tool`、统一管理生命周期。仅依赖 `com.Dino Code.tool`、SDK 与 JDK 标准库；不依赖 agent / tui / permission / conversation。
- **`Dino Code`（改造）**：在 `ToolRegistry.defaults()` 之后、`PermissionEngine` 与 `Dino CodeModel` 之前,加载 mcp 配置 → 启动 `McpManager` → 把 Manager 产出的工具注册进 registry → 退出时通过 `Runtime.getRuntime().addShutdownHook(...)`（或 try-with-resources）触发 `manager.close()`。
- **`tool` 包（零改）**：`ToolRegistry.register(...)` 与 `Tool` 接口本就是开放抽象,直接吃 `McpTool` 实例；`isReadOnly(...)` 对 MCP 工具返回正确值。
- **`agent` / `tui` 包（零改）**：工具流转链路对工具来源透明。
- **`permission` 包（零改）**：`friendlyName(...)` 对未知名原样返回 → 规则可写 `mcp__<server>__<tool>`；`categorize(...)` 在 `readOnly==true` 时走 `CategoryRead`、否则归 `CategoryExec` → 模式兜底矩阵自然命中；`extractTarget(...)` 对未知工具返回 `("", false, false)`,黑名单与沙箱自动跳过。
- **`llm` / provider（零改）**：工具定义透传,协议无关。

数据流（单次调用）：
```
agent.executeBatched(calls, mode)
  └→ engine.check(...)  → Allow → // 工具执行走 StreamingExecutor.executeAll(name, args)
       └→ McpTool.execute(args)                        [本章新增工具实现]
            ├→ CompletableFuture.orTimeout(30, SECONDS)
            ├→ session.callTool({ name: remoteName, arguments: map })
            └→ 拼接 text content / 映射 isError / 协议错转 isError
       └→ ToolResult{ content, isError }                ── 回灌 conv
```

## 核心数据结构

### `McpConfig` / `ServerConfig`（对外）
```java
package com.Dino Code.mcp;

// McpConfig 是 mcp_servers 在内存中的归一化形式（已展开 ${VAR}、已合并、已校验）。
public record McpConfig(java.util.Map<String, ServerConfig> servers) {}

// ServerConfig 是单个 MCP server 的完整定义。
public record ServerConfig(
        String type,                              // "stdio" | "http"
        String command,                           // stdio 必填
        java.util.List<String> args,              // stdio 可选
        java.util.Map<String, String> env,        // stdio 可选(已展开)
        String url,                               // http 必填
        java.util.Map<String, String> headers     // http 可选(已展开)
) {}
```

### `McpManager`（对外不透明）
```java
public final class McpManager implements AutoCloseable {
    private final Object lock = new Object();
    private final java.util.List<Session> sessions = new java.util.ArrayList<>();  // 已建立成功的 server 会话
    private final java.util.List<Tool>    tools    = new java.util.ArrayList<>();  // 已适配好的工具

    record Session(String name, McpClientSession cs) {}
}
```

`McpClientSession` 是官方 SDK 的客户端会话类型（包名以 SDK 实际为准,`io.modelcontextprotocol.client.McpAsyncClient` 一类）。

### 工具适配（包内私有）
```java
// McpTool 实现 com.Dino Code.tool.Tool。
final class McpTool implements com.Dino Code.tool.Tool {
    private final String  fullName;                        // "mcp__<server>__<tool>"
    private final String  remoteName;                      // server 上的原始工具名
    private final String  description;
    private final java.util.Map<String, Object> schema;    // JSON Schema 透传
    private final boolean readOnly;                        // 仅来自远端 annotations.readOnlyHint==true
    private final CallerSession session;                   // 接口形式持有,便于单测注入 stub
}

// CallerSession 是 McpTool 依赖的最小会话能力(生产实现包装 SDK 的 async client)。
interface CallerSession {
    CallToolResult callTool(String name, java.util.Map<String, Object> arguments)
            throws Exception;  // 同步阻塞;超时由调用方包 CompletableFuture.orTimeout 实现
}
```

## 核心接口

```java
// 加载并合并两层配置;返回归一化的 McpConfig。
// - root: 项目根(用来定位 <root>/.Dino Code.yaml)
// - 文件不存在 → 视为空层;格式非法 → 跳过该层 + stderr 告警(降级,N1)
// - 内部完成 ${VAR} 展开与字段校验(非法 server 直接剔除,N2)
// - 永不抛出 checked 异常;签名仅声明运行时降级行为
public static McpConfig loadConfig(java.nio.file.Path root);

// 启动 McpManager:并发连接所有 server,每个 server 30s 超时,失败仅跳过 + 告警。
// 阻塞直到所有 server 的尝试结束(成功 / 失败 / 超时)。
// version 透传到 implementation.version(便于 server 端识别 Dino Code 版本)。
public static McpManager start(McpConfig cfg, String version);

// 返回适配好的工具列表(按 server 名 → 工具名 稳定排序)。
public java.util.List<com.Dino Code.tool.Tool> tools();

// 关闭所有会话(stdio 子进程终止、HTTP DELETE);总超时 5s 兜底,绝不阻塞退出。
@Override public void close();
```

## 模块设计

### `dev/Dino Code/mcp/ConfigLoader.java`
**职责：** 加载两层 YAML、合并、展开 `${VAR}`、校验。
**关键点：**
- 内部 record `RawServer(String type, String command, List<String> args, Map<String,String> env, String url, Map<String,String> headers)`。
- 用 `org.snakeyaml.engine.v2.api.Load` 把文件解析成 `Map<String, Object>`,读 `mcp_servers` 段后逐项手动绑定到 `RawServer`。
- `loadFile(Path path) -> Map<String, RawServer>`:文件不存在 → 空 map;解析失败 → 空 map + stderr 告警(降级)。
- `expandVars(String s) -> Expansion(String out, List<String> undefined)`:正则 `\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}` 匹配,用 `System.getenv(name)` 取值,未定义变量名记录到 undefined(供告警)。**仅作用于 env / headers 的值**。
- `applyExpansion(String name, RawServer srv)`:对 env / headers 的每个 value 跑 `expandVars`,原地替换;未定义变量在 stderr 输出 `[mcp] warn: undefined env var ${X} referenced by server <name>`。
- `mergeServers(Map<String,RawServer> user, Map<String,RawServer> project)`:新建 `LinkedHashMap`,复制 user,遍历 project 直接整对象覆盖同名 key。
- `validateServer(String name, RawServer srv) -> Optional<ServerConfig>`:
  - `type` 必为 `"stdio"` 或 `"http"`,否则跳过；
  - `stdio` 必填 `command`;`http` 必填 `url`;缺失则跳过；
  - 违规时 stderr 告警 `[mcp] warn: skip server <name>: <reason>`。
- `loadConfig(Path root)`:
  - 用户级 = `System.getProperty("user.home")` 取家目录 + `/.Dino Code/config.yaml`;项目级 = `root.resolve(".Dino Code.yaml")`。
  - 两层各自 `loadFile` + `applyExpansion`;任一层解析失败 stderr 一行告警并跳过(该层视为空)。
  - `mergeServers` 后逐个 `validateServer`,组装 `McpConfig`。

### `dev/Dino Code/mcp/McpManager.java`
**职责：** 连接 server、缓存会话、关闭。
**关键点：**
- `start(cfg, version)`:
  - 内部 `McpManager mgr = new McpManager();`
  - 对每个 server 用 `Thread.startVirtualThread(() -> connectOne(name, srv, version, mgr));` 并发起 virtual thread;`CountDownLatch` 等齐。
  - `connectOne(...)` 内:
    - `CompletableFuture<Void> deadline = new CompletableFuture<>();`
    - 调度器 `ScheduledExecutorService.schedule(() -> deadline.completeExceptionally(new TimeoutException()), 30, SECONDS)`。
    - 按 `type` 构造 transport:
      - **stdio**:`ServerParameters params = ServerParameters.builder(srv.command()).args(srv.args()).env(mergeOsEnv(srv.env())).build();` → `transport = new StdioClientTransport(params);`(SDK 内部启动子进程,stderr 透传到宿主 stderr)。
      - **http**:`HttpClient hc = HttpClient.newBuilder().build();` → `transport = HttpClientStreamableHttpTransport.builder(srv.url()).httpClient(hc).customizeRequest(rb -> srv.headers().forEach(rb::header)).disableServerSentEvents(true).build();`(SDK 暴露的 `customizeRequest` 钩子在每次请求前注入 headers)。
    - `McpAsyncClient client = McpClient.async(transport).clientInfo(new Implementation("Dino Code", version)).build();`
    - `client.initialize().block(Duration.ofSeconds(30));` ← SDK 自动完成 initialize 握手;超时抛 `RuntimeException`,异常 → stderr 一行告警 + return。
    - `ListToolsResult lst = client.listTools().block(Duration.ofSeconds(30));`;异常 → stderr 告警 + `client.closeGracefully().block(Duration.ofSeconds(5))`(避免连了但列工具失败的连接泄漏) + return。
    - 对每个返回工具调 `adaptTool(name, t, new AsyncCallerSession(client))`;成功的 push 到临时列表。
    - `synchronized (mgr.lock)`:`mgr.sessions.add(...)`;`mgr.tools.addAll(adapted)`。
  - `latch.await()` 后稳定排序 `mgr.tools`(先 server 名再 tool 名;`fullName` 已含 server 前缀,按 `Comparator.comparing(Tool::name)`)。
  - 返回 `mgr`。
- `mergeOsEnv(Map<String,String> extra)`:`new HashMap<>(System.getenv())` 后用 `extra.forEach(map::put)` 覆盖,返回。
- `customizeRequest`:SDK 暴露的 `Consumer<HttpRequest.Builder>` 钩子;`headers.forEach(builder::header)` 即把每个 header 注入到每次请求。
- `tools()`:返回 `List.copyOf(this.tools)`(防外部修改)。
- `close()`:
  - 每个 session 起 virtual thread `Thread.startVirtualThread(() -> session.cs().closeGracefully().block())`;
  - `CountDownLatch` + `latch.await(5, SECONDS)` 兜底;超过 5s 直接 return,不再等。

### `dev/Dino Code/mcp/McpTool.java`
**职责：** 把 SDK 返回的 `McpSchema.Tool` 适配为 `com.Dino Code.tool.Tool`。
**关键点：**
- `adaptTool(String serverName, McpSchema.Tool t, CallerSession cs) -> Optional<McpTool>`:
  - `String fullName = "mcp__" + serverName + "__" + t.name();`
  - **禁用字符校验**:`private static final java.util.regex.Pattern VALID_NAME = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$");`;不通过 → `Optional.empty()` + stderr 告警 `[mcp] warn: skip tool <fullName>: name contains illegal characters`。
  - `String descr = t.description();` 为空时兜底 `"来自 MCP server " + serverName + " 的工具 " + t.name()`。
  - `Map<String,Object> schema`:把 `t.inputSchema()` 通过 SDK 自带的 Jackson `ObjectMapper` 序列化为 `Map<String,Object>`;解出 null 或空 map 时给 `Map.of("type", "object")` 兜底(避免 provider 拒收空 schema)。
  - `boolean readOnly = t.annotations() != null && Boolean.TRUE.equals(t.annotations().readOnlyHint());`(null-safe)。
- 实现 `Tool` 接口的 5 个方法(`name()` / `description()` / `schema()` / `readOnly()` 直接返回字段;`execute(args)` 见下)。
- `execute(JsonNode args)`(返回 `ToolResult`):
  - `Map<String,Object> argMap = (args == null || args.isNull()) ? Map.of() : MAPPER.convertValue(args, new TypeReference<>(){});` 失败 → `new ToolResult("参数解析失败: " + e.getMessage(), true)`。
  - `try { CallToolResult res = CompletableFuture.supplyAsync(() -> session.callTool(remoteName, argMap), VIRTUAL_EXEC).orTimeout(30, TimeUnit.SECONDS).get(); ... }` 失败/超时 → `new ToolResult("MCP 工具调用失败: " + cause.getMessage(), true)`(含 `TimeoutException`)。
  - 否则遍历 `res.content()`:对 `TextContent` 把 `.text()` 追加到 `StringBuilder`(块间 `"\n"` 分隔);非 text 块计数,首次出现时通过包级 `ConcurrentHashMap<String, Boolean>` `putIfAbsent(fullName, Boolean.TRUE)` 限一次,stderr 告警 `[mcp] warn: tool <fullName> returned non-text content blocks (dropped)`。
  - 返回 `new ToolResult(collected, res.isError() != null && res.isError())`。

### `dev/Dino Code/Dino Code.java`(改造)
位置:在 `ToolToolRegistry registry = ToolRegistry.defaults();` 之后、`PermissionEngine engine = new PermissionEngine(root);` 之前插入:
```java
McpConfig mcpCfg = ConfigLoader.loadConfig(root);
McpManager mgr   = McpManager.start(mcpCfg, version);
Runtime.getRuntime().addShutdownHook(new Thread(mgr::close, "mcp-shutdown"));
for (Tool t : mgr.tools()) {
    registry.register(t);
}
```
(`root` 复用现有的 `Path.of("").toAbsolutePath()`;`version` 复用 `Main.VERSION` 常量。)

### `dev/Dino Code/SmokeDino Code.java`(不改)
smoke 用 `ToolRegistry.defaults()` 不接 MCP,保持非交互简单。

## 文件组织

```
Dino Code/
├── src/main/java/com/Dino Code/mcp/
│   ├── McpConfig.java       — 新:McpConfig / ServerConfig record
│   ├── ConfigLoader.java    — 新:loadConfig、loadFile、expandVars、mergeServers、validateServer
│   ├── McpManager.java      — 新:McpManager、Session、start(并发+30s 超时)、close(5s 兜底)、tools、mergeOsEnv、headers 注入
│   └── McpTool.java         — 新:McpTool、CallerSession、AsyncCallerSession、adaptTool、execute
├── src/test/java/dev/Dino Code/mcp/
│   ├── ConfigLoaderTest.java — 新:两层合并 / 变量展开 / 字段校验 / 降级 单测
│   ├── McpManagerTest.java   — 新:连接成功/失败/超时、close 不死锁、共享状态并发安全
│   └── McpToolTest.java      — 新:命名拼接、禁用字符跳过、execute 各分支(成功/远端 isError/超时/协议错/非 text 块)
├── src/main/java/com/Dino Code/Dino Code.java — 改:装配 McpManager,注册 MCP 工具,addShutdownHook(mgr::close)
├── build.gradle.kts                  — 改:添加 io.modelcontextprotocol.sdk:mcp 依赖
├── docs/mcp/
│   ├── spec.md / plan.md / task.md / checklist.md
│   └── mcp-servers.example.yaml — 新:配置示例(用 ${VAR})
└── (其它包零改)
```

## 技术决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 协议层实现 | 官方 Java SDK(`io.modelcontextprotocol.sdk:mcp`) | 用户拍板;避免自研 JSON-RPC/握手/帧;SDK 已处理 stdio 与 Streamable HTTP |
| 配置文件位置 | 项目级 `<root>/.Dino Code.yaml` + 用户级 `~/.Dino Code/config.yaml` | 用户拍板;项目级 dotfile 一眼可见、与现有 `.Dino Code/config.yaml`(providers 凭据)分离 |
| 配置层数 | 仅两层,无本地级 | 用户拍板;`${VAR}` 已让密钥不入配置,本地层冗余 |
| 合并语义 | server 名维度,项目级完整覆盖 | 避免字段级半合并出畸形 server |
| server 类型字段 | 显式 `type: stdio\|http` | 不靠字段嗅探(防止误判);未来扩展易加(如 sse) |
| 变量展开范围 | 仅 env/headers 的值 | 避免 command/args/server 名/工具名被环境间接影响;凭据走 env/headers 已足够 |
| 未定义变量 | 空串 + 一次性告警(不阻断) | server 自决无凭据时是否能跑;Dino Code 不替它拍板 |
| 工具命名 | `mcp__<server>__<tool>` | 用户拍板;Claude Code 风格;LLM 工具名安全字符;一眼识别来源 |
| 启动连接策略 | 同步进 TUI 前完成 + virtual thread 并发 + 每 server 30s 超时 + 失败跳过 | 进 TUI 时工具集稳定;virtual thread 极廉价,N 个 server 并发不耗资源;隔离避免单 server 拖死启动 |
| 调用超时 | 30s 硬编码,转 isError | 与连接同值;不中断 Loop;避免长卡 |
| readOnly 适配 | 严格只信 `annotations.readOnlyHint==true` | 默认走 Ask,最严;声明只读才放行 |
| 资源/提示词/采样/roots | 不实现 | 本章只覆盖工具能力 |
| 独立 SSE 通道 | `disableServerSentEvents(true)` | 只用请求-响应;省一条长连接;减少复杂度 |
| 非 text 内容块 | 静默丢弃 + 一次性告警 | 模型只能消费文本;丢弃比假装回灌更诚实 |
| 错误回灌 | 协议错/超时均转 isError | 与不中断 Loop 的契约一致 |
| 退出关闭 | 每 session.closeGracefully 并发 virtual thread + 5s 总超时兜底 | 避免某 server 卡死阻塞退出 |
| permission 接入方式 | 零改动;靠 `friendlyName` 原样 + `categorize` 按 readOnly 优先 | 复用现成链路;权限规则可写 `mcp__server__tool` 与 `mcp__server__*` |
| HTTP 自定义 headers | SDK 暴露的 `customizeRequest(Consumer<HttpRequest.Builder>)` 钩子注入 | SDK 原生支持,不需要包一层 `HttpClient.Interceptor` |
| OAuth | 不实现完整流程 | 用户预换 token 写 headers;本章范围最小化 |
| execute 接口注入 | McpTool 持 `CallerSession` 接口而非具体 SDK client | 单测可注入 stub;生产代码无运行时开销 |
| 异步桥接 | SDK 的 `Mono<T>` 一律 `.block(Duration.ofSeconds(30))` 转同步;调用层用 `CompletableFuture.orTimeout` 二次兜底 | Dino Code 内部以同步阻塞为主线(virtual thread 顶替线程池);避免把 Reactor 类型外泄到 mcp 包之外 |

## 模块交互

```
Dino Code.main()
  ├─ ToolToolRegistry registry = ToolRegistry.defaults();           // 6 内置工具
  ├─ McpConfig cfg = ConfigLoader.loadConfig(root);             // 读两层 yaml + ${VAR} 展开 + 校验
  ├─ McpManager mgr = McpManager.start(cfg, version);           // virtual thread 并发连接所有 server,30s/各
  │     └─ 对每个 server:
  │         ├─ 构造 transport(stdio: StdioClientTransport / http: HttpClientStreamableHttpTransport)
  │         ├─ McpClient.async(transport).clientInfo(...).build() + .initialize().block()(自动 initialize 握手)
  │         ├─ .listTools().block()
  │         └─ adaptTool 包装成 McpTool
  ├─ for (Tool t : mgr.tools()) registry.register(t);
  ├─ PermissionEngine engine = new PermissionEngine(root);
  ├─ new Dino CodeModel(.registry(registry).engine(engine).build().run();
  └─ Runtime.getRuntime().addShutdownHook(new Thread(mgr::close)); // stdio 终止子进程,HTTP DELETE 会话;5s 总超时兜底
```

调用链(Agent 视角,工具来源透明):
```
agent.executeBatched(calls, mode)
  └ engine.check(mode, call, registry.isReadOnly(tc.toolName()))
       (MCP 工具:friendlyName 原样;categorize:readOnly==true→Read, 否则→Exec;
        extractTarget(未知工具)→isFile=false,target="" → 黑名单/沙箱自动跳过)
  └ Allow → // 工具执行走 StreamingExecutor.executeAll(name, args)
       └ McpTool.execute(args)
            ├ CompletableFuture.orTimeout(30, SECONDS)
            └ session.callTool → 拼接 text / 映射 isError / 协议错转 isError
  └ ToolResult 回灌 conv
```

依赖方向(无环):`Main → mcp → { tool, llm, SDK, JDK 标准库 }`;`mcp` 不依赖 `agent / tui / permission / conversation`。
