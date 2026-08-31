# MCP 客户端 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 改   | `build.gradle.kts` | 添加 `io.modelcontextprotocol.sdk:mcp` 依赖 |
| 新建 | `src/main/java/com/Dino Code/mcp/McpConfig.java` | `McpConfig` / `ServerConfig` record |
| 新建 | `src/main/java/com/Dino Code/mcp/ConfigLoader.java` | `loadConfig`、`loadFile`、`expandVars`、`applyExpansion`、`mergeServers`、`validateServer` |
| 新建 | `src/test/java/dev/Dino Code/mcp/ConfigLoaderTest.java` | 两层合并 / `${VAR}` 展开 / 字段校验 / 降级 单测 |
| 新建 | `src/main/java/com/Dino Code/mcp/McpTool.java` | `CallerSession` 接口、`McpTool`、`AsyncCallerSession`、`adaptTool`、`execute`、非 text 块告警 once 池 |
| 新建 | `src/test/java/dev/Dino Code/mcp/McpToolTest.java` | 命名拼接 / 禁用字符 / `execute` 成功 / 远端 isError / 超时 / 协议错 / 非 text 块跳过 单测 |
| 新建 | `src/main/java/com/Dino Code/mcp/McpManager.java` | `McpManager`、`Session`、`start`(并发 + 30s 超时)、`close`(5s 兜底)、`tools`、`mergeOsEnv`、headers 注入 |
| 新建 | `src/test/java/dev/Dino Code/mcp/McpManagerTest.java` | 连接成功/失败/超时、`close` 不死锁、并发写共享状态安全 单测 |
| 改   | `src/main/java/com/Dino Code/Dino Code.java` | 装配 `ConfigLoader.loadConfig`、`McpManager.start`、注册 MCP 工具、`addShutdownHook(mgr::close)` |
| 新建 | `docs/mcp/mcp-servers.example.yaml` | 配置示例(含 stdio / http 各一个,用 `${VAR}`) |

---

## T1: 添加 MCP Java SDK 依赖

**文件：** `build.gradle.kts`
**依赖：** 无
**步骤：**
1. 在 `<dependencies>` 节加入：
   ```xml
   <dependency>
     <groupId>io.modelcontextprotocol.sdk</groupId>
     <artifactId>mcp</artifactId>
     <version>0.10.0</version>  <!-- 以 Gradle Central 上最新稳定为准 -->
   </dependency>
   ```
2. `./gradlew -q dependency:resolve` 拉取依赖；查看本地仓库确认 `mcp-<ver>.jar` 出现。
3. 写一段最小试编(可直接放进后续 `McpTool.java` 的 import 中)：`import io.modelcontextprotocol.client.McpClient;` 并 `McpClient.async(/* dummy transport */)`,验证可用。

**验证：** `./gradlew shadowJar` 编译通过；`./gradlew dependencies | grep modelcontextprotocol` 有命中。

## T2: 配置类型与加载(含两层合并 + 变量展开 + 字段校验)

**文件：** `src/main/java/com/Dino Code/mcp/{McpConfig,ConfigLoader}.java`、`src/test/java/dev/Dino Code/mcp/ConfigLoaderTest.java`
**依赖：** T1
**步骤：**
1. 定义对外 record `McpConfig(Map<String, ServerConfig> servers)`、`ServerConfig(String type, String command, List<String> args, Map<String,String> env, String url, Map<String,String> headers)`(见 plan.md「核心数据结构」)。
2. 定义包内 record `RawServer(...)`,字段同 `ServerConfig`(但全部可变成 null,代表"未填")。
3. `static Map<String, RawServer> loadFile(Path path)`：
   - 文件不存在 → 空 `Map`；
   - 用 `org.snakeyaml.engine.v2.api.Load(LoadSettings.builder().build())` 解析为 `Map<String, Object>`；
   - 读取 `mcp_servers` 字段,逐项手动绑定到 `RawServer`(字段 `base_url` 不在此处,但 `args` / `env` / `headers` 注意类型 cast 检查)；
   - `YamlEngineException` / `ClassCastException` → 空 `Map` + stderr 一行告警(降级)。
4. `static record Expansion(String out, List<String> undefined) {}`,`static Expansion expandVars(String s)`：
   - 正则 `Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}")` 匹配；用 `System.getenv(name)` 取值；未定义记录变量名到 `undefined`。
5. `static void applyExpansion(String name, RawServer srv)`(返回新 `RawServer` 或就地修改 map,实现自选)：
   - 对 `srv.env()`、`srv.headers()` 的每个值跑 `expandVars`,原地替换；
   - 收集所有 undefined 变量名,去重；首次出现时 `System.err.printf("[mcp] warn: undefined env var ${%s} referenced by server %s%n", v, name);`。
6. `static Map<String, RawServer> mergeServers(Map<String, RawServer> user, Map<String, RawServer> project)`：
   - 新建 `LinkedHashMap`,复制 user；
   - 遍历 project,直接整对象覆盖同名 key。
7. `static Optional<ServerConfig> validateServer(String name, RawServer srv)`：
   - `srv.type()` 必为 `"stdio"` 或 `"http"`,否则跳过；
   - `stdio` 必填 `command`；`http` 必填 `url`；缺失则跳过；
   - 违规时 `System.err.printf("[mcp] warn: skip server %s: %s%n", name, reason);`；返回 `Optional.empty()`。
8. `public static McpConfig loadConfig(Path root)`：
   - 用户级 = `Path.of(System.getProperty("user.home"), ".Dino Code", "config.yaml")`(取家目录失败时跳过用户层不致错)；项目级 = `root.resolve(".Dino Code.yaml")`。
   - 两层各自 `loadFile`；解析失败(非"文件不存在") → 一行 stderr 告警 + 该层视为空。
   - 对每层各 server 跑 `applyExpansion`。
   - `mergeServers` 后逐个 `validateServer`,收齐合法 server 组装 `McpConfig`。
   - 永不抛出 checked 异常(签名也不声明)。

**验证：** `./gradlew shadowJar`；`./gradlew test -Dtest=ConfigLoaderTest` 覆盖：
- 两文件缺失 → `McpConfig.servers()` 为空、无异常；
- 仅用户级 / 仅项目级 / 都有(同名 server 项目级胜出,断言字段为项目级值)；
- 文件格式非法 → 跳过该层、其它正常加载、stderr 有告警(测试中用 `System.setErr(...)` 重定向断言)；
- `${VAR}` 已定义 → 展开为环境值；未定义 → 空串 + 告警；`command` / `args` 中含 `${VAR}` → 不展开(保留字面量)；
- `type` 缺失 / `type` 非法 / stdio 缺 command / http 缺 url → 该 server 被跳过,其它 server 不受影响。

## T3: 工具适配(McpTool)

**文件：** `src/main/java/com/Dino Code/mcp/McpTool.java`、`src/test/java/dev/Dino Code/mcp/McpToolTest.java`
**依赖：** T1
**步骤：**
1. `import io.modelcontextprotocol.spec.McpSchema;` `import io.modelcontextprotocol.spec.McpSchema.CallToolResult;` `import com.Dino Code.tool.Tool;` `import com.Dino Code.tool.ToolResult;`。
2. 定义包内最小接口 `interface CallerSession { CallToolResult callTool(String name, Map<String,Object> arguments) throws Exception; }`,与 `final class McpTool implements Tool`(见 plan.md「核心数据结构」)。
3. 包级常量：
   ```java
   private static final java.util.regex.Pattern VALID_NAME =
           java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$");
   private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
           new com.fasterxml.jackson.databind.ObjectMapper();
   private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> NON_TEXT_WARNED =
           new java.util.concurrent.ConcurrentHashMap<>();
   ```
4. 实现 `Tool` 接口的 4 个 getter（`name()` / `description()` / `schema()` / `readOnly()`,均直接返回字段）。
5. `static Optional<McpTool> adaptTool(String serverName, McpSchema.Tool t, CallerSession cs)`：
   - `String fullName = "mcp__" + serverName + "__" + t.name();`
   - `if (!VALID_NAME.matcher(fullName).matches()) { System.err.printf("[mcp] warn: skip tool %s: name contains illegal characters%n", fullName); return Optional.empty(); }`
   - `String descr = (t.description() == null || t.description().isBlank()) ? "来自 MCP server " + serverName + " 的工具 " + t.name() : t.description();`
   - `Map<String, Object> schema = MAPPER.convertValue(t.inputSchema(), new TypeReference<Map<String, Object>>(){});` 若 `schema == null || schema.isEmpty()` → `schema = Map.of("type", "object");`。
   - `boolean readOnly = t.annotations() != null && Boolean.TRUE.equals(t.annotations().readOnlyHint());`
   - 返回 `Optional.of(new McpTool(fullName, t.name(), descr, schema, readOnly, cs));`。
6. `public ToolResult execute(com.fasterxml.jackson.databind.JsonNode args)`：
   - `Map<String, Object> argMap;`
   - `if (args == null || args.isNull()) { argMap = Map.of(); } else { try { argMap = MAPPER.convertValue(args, new TypeReference<>(){}); } catch (IllegalArgumentException e) { return new ToolResult("参数解析失败: " + e.getMessage(), true); } }`
   - 调用 + 30s 超时：
     ```java
     CallToolResult res;
     try {
         res = java.util.concurrent.CompletableFuture
                 .supplyAsync(() -> { try { return session.callTool(remoteName, argMap); } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); } },
                              java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                 .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                 .get();
     } catch (Exception e) {
         Throwable cause = (e.getCause() != null) ? e.getCause() : e;
         return new ToolResult("MCP 工具调用失败: " + cause.getMessage(), true);
     }
     ```
   - 遍历 `res.content()`：
     - `instanceof McpSchema.TextContent tc` → `sb.append(tc.text()).append('\n');`
     - 其余分支 → 计数 + `NON_TEXT_WARNED.putIfAbsent(fullName, Boolean.TRUE)` 限一次,触发时 stderr 告警 `[mcp] warn: tool <fullName> returned non-text content blocks (dropped)`。
   - 返回 `new ToolResult(sb.toString().stripTrailing(), Boolean.TRUE.equals(res.isError()));`。

**验证：** `./gradlew test -Dtest=McpToolTest` 覆盖：
- 合法 server 名 + 工具名 → `adaptTool` 返回非空；含 `.` / `@` 等非法字符 → 返回空 + 告警；
- description 空 → 兜底文案出现；schema null → `{"type":"object"}`；schema 透传成功；
- `t.annotations() == null` → `readOnly==false`(不抛 NPE)；`readOnlyHint==true` → `readOnly==true`；
- `execute`：注入 stub `CallerSession`,覆盖：成功(多 text 块拼接) / 远端 `isError=true` 映射 / `callTool` 抛异常转 `isError=true` / 阻塞至超时(用 stub `Thread.sleep(60_000)` + 测试中把超时改成 200ms,断言 `TimeoutException` 转 isError) / 非 text 块跳过 + collected 仅含 text。

## T4: 连接管理器(McpManager)

**文件：** `src/main/java/com/Dino Code/mcp/McpManager.java`、`src/test/java/dev/Dino Code/mcp/McpManagerTest.java`
**依赖：** T2、T3
**步骤：**
1. 定义 `public final class McpManager implements AutoCloseable` 与内嵌 `record Session(String name, McpAsyncClient client) {}`(见 plan.md)。
2. headers 注入：SDK 的 `HttpClientStreamableHttpTransport.builder(...).customizeRequest(rb -> ...)` 钩子已足够,不需要自行包 `HttpClient`。若 SDK 没有该钩子(版本差异),退一步用：
   ```java
   HttpClient hc = HttpClient.newBuilder()
           .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
           .build();
   // 然后构造一个 `HttpRequest.Builder` Consumer 钩子注入 headers
   ```
3. `static List<String> mergeOsEnv(Map<String,String> extra)`：把 `System.getenv()` 转 `LinkedHashMap`,用 `extra.forEach(map::put)` 覆盖同名键,再 `map.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()`(若 SDK 期望 `Map<String,String>`,直接还原为新 map)。
4. `public static McpManager start(McpConfig cfg, String version)`：
   - 内部 `McpManager mgr = new McpManager();`
   - 对 `cfg.servers().entrySet()` 每个 `(name, srv)`,`Thread.startVirtualThread(() -> connectOne(mgr, name, srv, version, latch));`。
   - 用 `CountDownLatch latch = new CountDownLatch(cfg.servers().size());` 等齐。
   - `latch.await();` 后稳定排序 `mgr.tools`(`Comparator.comparing(Tool::name)`,因为 `fullName` 已带 `mcp__<server>__` 前缀)。
   - 返回 `mgr`。
5. `static void connectOne(McpManager mgr, String name, ServerConfig srv, String version, CountDownLatch latch)`：
   - `try { ... } finally { latch.countDown(); }`。
   - 按 `srv.type()` 构造 transport：
     - **stdio**：`StdioClientTransport transport = new StdioClientTransport(ServerParameters.builder(srv.command()).args(srv.args()).env(mergeOsEnv(srv.env())).build());`
     - **http**：`HttpClient hc = HttpClient.newBuilder().build();` `var transport = HttpClientStreamableHttpTransport.builder(srv.url()).httpClient(hc).customizeRequest(rb -> srv.headers().forEach(rb::header)).disableServerSentEvents(true).build();`
   - `McpAsyncClient client = McpClient.async(transport).clientInfo(new McpSchema.Implementation("Dino Code", version)).build();`
   - `client.initialize().block(Duration.ofSeconds(connectTimeoutSec));` 异常 → stderr 告警 `[mcp] warn: connect server <name> failed: <err>` + return。
   - `ListToolsResult lst = client.listTools().block(Duration.ofSeconds(connectTimeoutSec));` 异常 → stderr 告警 + `client.closeGracefully().block(Duration.ofSeconds(5));` + return。
   - 对每个 `t : lst.tools()` 调 `McpTool.adaptTool(name, t, new AsyncCallerSession(client))`；成功的入临时 `List<Tool>`。
   - `synchronized (mgr.lock) { mgr.sessions.add(new Session(name, client)); mgr.tools.addAll(adapted); }`。
6. `public List<Tool> tools()`：返回 `List.copyOf(this.tools)`(防外部修改)。
7. `@Override public void close()`：
   - 对每个 `session` 起 `Thread.startVirtualThread(() -> { try { session.client().closeGracefully().block(Duration.ofSeconds(2)); } catch (Exception ignored) {} done.countDown(); });`
   - `done.await(closeTimeoutSec, SECONDS);` 兜底；超时即 return 不等。
8. 把 30s 与 5s 实现成包级 `static volatile long connectTimeoutSec = 30L;` / `static volatile long closeTimeoutSec = 5L;`,便于单测 setup 中临时改小,结束 restore。

**验证：** `./gradlew test -Dtest=McpManagerTest` 覆盖：
- 空 `cfg` → `McpManager` 无 sessions、`tools()` 空、`close()` 立即返回；
- 失败隔离：构造一个 stdio server 指向不存在的 command + 一个用单测注入的 stub"server"(借助接口替身把 SDK 调用替换),断言 stub 工具被注册、失败 server 仅产生告警；
- 超时收尾：注入一个会卡住的连接 stub(让 `initialize().block(...)` 阻塞),把超时改为 200ms,断言超时窗口内被跳过；
- close 兜底：注入一个 closeGracefully 阻塞的 session,断言 `close()` 在(测试中改短的)兜底时间内返回；
- 并发安全：用 `./gradlew test`(JUnit 5)的多线程用例 + `@RepeatedTest(50)` 反复跑 10+ server 并发,无 `ConcurrentModificationException`、无丢工具。

## T5: Main 接线

**文件：** `src/main/java/com/Dino Code/Dino Code.java`
**依赖：** T2、T3、T4
**步骤：**
1. import `com.Dino Code.mcp.{ConfigLoader, McpConfig, McpManager};` 与 `com.Dino Code.tool.Tool;`(若没有)。
2. 在 `ToolToolRegistry registry = ToolRegistry.defaults();` 行之后、`PermissionEngine engine = new PermissionEngine(root);` 之前插入：
   ```java
   McpConfig mcpCfg = ConfigLoader.loadConfig(root);
   McpManager mgr   = McpManager.start(mcpCfg, VERSION);
   Runtime.getRuntime().addShutdownHook(new Thread(mgr::close, "mcp-shutdown"));
   for (Tool t : mgr.tools()) {
       registry.register(t);
   }
   ```
3. `root` 复用现有 `Path.of("").toAbsolutePath()` 结果(已在 `Dino Code` 中)；`VERSION` 复用 `public static final String VERSION` 常量。

**验证：** `./gradlew shadowJar`；无 MCP 配置时 `java -jar build/libs/Dino Code.jar` 能正常进 TUI、内置 6 工具可用；配置一个 command 不存在的 stdio server 时进 TUI 不阻塞、stderr 显示连接失败告警。

## T6: 配置示例

**文件：** `docs/mcp/mcp-servers.example.yaml`
**依赖：** 无(可与 T2 并行)
**步骤：**
1. 内容(用 YAML 注释说明放置位置与覆盖语义)：
   ```yaml
   # 项目级放 <root>/.Dino Code.yaml；用户级放 ~/.Dino Code/config.yaml。
   # 同名 server 项目级完整覆盖用户级。
   # env / headers 的值支持 ${VAR} 从宿主环境变量展开；command/args 不展开。
   mcp_servers:
     github:
       type: stdio
       command: npx
       args: ["-y", "@modelcontextprotocol/server-github"]
       env:
         GITHUB_TOKEN: "${GITHUB_TOKEN}"
     local-sqlite:
       type: stdio
       command: python
       args: ["-m", "mcp_server_sqlite", "--db", "./data.db"]
     example-http:
       type: http
       url: "https://mcp.example.com/mcp"
       headers:
         Authorization: "Bearer ${EXAMPLE_TOKEN}"
   ```

**验证：** 在 `ConfigLoaderTest` 增加一个用例,读取此示例文件断言三个 server 都被解析成功。

## T7: tmux 端到端实跑(CLAUDE.md 开发原则)

**文件：** —
**依赖：** T1–T6
**步骤：**
1. 准备一个真实可用的 stdio MCP server。优先用 `npx -y @modelcontextprotocol/server-everything`(官方示例 server,自带 echo / add 等基础工具)；若无 npx,可临时用一个最小 Python/JS server。
2. 在项目根写一个临时 `.Dino Code.yaml` 指向它：
   ```yaml
   mcp_servers:
     demo:
       type: stdio
       command: npx
       args: ["-y", "@modelcontextprotocol/server-everything"]
   ```
3. `tmux` 起 Dino Code：
   - 启动日志(stderr)显示 server 连接成功 + 工具数；TUI 状态栏正常；
   - 让模型调用 `mcp__demo__echo` 一类工具：default 模式下弹人在回路 → 允许本次 → 工具结果回灌 → 模型续答；
   - 选"永久允许"后,本地权限规则被写入；重启 Dino Code 后再调同工具不再弹窗(验证永久规则与 MCP 命名空间联动)；
   - 切到 bypassPermissions：调用不弹窗；但让模型跑 `rm -rf /` 仍被内置黑名单拦下(MCP 工具不绕过黑名单的内置作用域)；
   - Esc 取消弹窗：干净回到 idle,不退出程序；
   - `q` 退出 Dino Code 后 `ps -ef | grep server-everything` 确认子进程已终止；
4. 配置一个 command 不存在的 server + 一个能跑的 server：启动 stderr 有失败告警,能跑的 server 工具仍可用。

**验证：** 上述全部观察通过；删除临时 `.Dino Code.yaml`,恢复项目根干净。

## T8: 全量编译测试与规范

**文件：** —
**依赖：** T1–T7
**步骤：**
1. `./gradlew compileJava` (代码风格由 IDE 保证)(google-java-format 应无差异；如有则 `./gradlew spotless:apply`)。
2. `./gradlew shadowJar`(应无错误、无未使用 import 告警)。
3. `./gradlew test`(覆盖 config、conversation、tool、agent、prompt、permission、tui、**mcp** 三组单测)。
4. 重点跑 `./gradlew test -Dtest='com.Dino Code.mcp.*'` 多次(`-Dsurefire.rerunFailingTestsCount=3`),确认 virtual thread 并发无偶发失败。
5. `git grep -E '(Bearer|sk-|ghp_|github_pat_)[A-Za-z0-9_-]{16,}'`(应无命中：凭据不落盘)。
6. `git check-ignore -q docs/mcp/mcp-servers.example.yaml` 不需要忽略(示例只含 `${VAR}`)。

**验证：** 全部通过。

## 执行顺序

```
T1(SDK 依赖) ─┬─→ T2(config) ─┐
              │                ├─→ T4(manager) ─→ T5(Main 接线) ─→ T7(tmux 实跑) ─→ T8(规范)
              └─→ T3(tool)   ─┘
                                 └─→ T6(配置示例)(可与 T2 并行)
```
依赖：T2,T3 ← T1；T4 ← {T2,T3}；T5 ← {T2,T3,T4}；T6 独立于 T3、T4(可在 T2 完成后做)；T7 ← T1–T5；T8 ← 全部。
