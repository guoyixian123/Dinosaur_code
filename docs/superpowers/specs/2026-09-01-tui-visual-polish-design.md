# TUI 视觉优化设计(恐龙 Banner + Diff 可视化 + 输入框恐龙元素 + 主界面丰富)

日期:2026-09-01
状态:已与用户逐项确认(路线 A · 实心 DINO CODE 字标 · 全界面去 emoji · 绿框鳞片输入框 · 纯色 ❯)

## 1. 目标与范围

对 Dino Code 的 TUI 层(`dinocode/tui` 包)做纯渲染层视觉升级,不改变交互模型与任何业务行为:

1. **启动 Banner**:用 ANSI Shadow 实心块字体输出 `DINO CODE` 大字标(用户明确要"dinocode"完整、实心、清晰),下附状态面板(模型/会话/模式)。
2. **文件修改可视化**:Edit/Write 工具执行后,在工具结果摘要处渲染**行级红绿 diff**(改了什么、改在哪几行、新增多少行),替代现在的一句"已修改 path"。
3. **主界面丰富**:新增会话状态行(模式 · 模型 · 累计 tokens)与提示帮助行,输入框加绿色边框 + 鳞片纹样徽标 `▄▀▄▄▀▄`。
4. **全界面去 emoji**:移除 Spinner(`🦖 thinking…`)与退出语(`再见 🦖`)中的 emoji,统一为块字符 + 中文文案。

**明确不做**(YAGNI):
- 不引入 Lanterna 等全屏 TUI 库(用户已选路线 A:保持流式终端,理由:长回复的终端原生滚动/复制是刚需,且 15 章积累的交互逻辑不受影响)。
- 不做鼠标支持、不做侧栏面板、不改权限/会话/命令业务逻辑。
- 提示符不加图标(用户选:纯色 `❯`,靠颜色区分模式)。

## 2. 现状与改动点

| 文件 | 现状 | 改动 |
|---|---|---|
| `tui/Banner.java` | 3 行块字符恐龙(用户评价"看不出是龙") | 重写:ANSI Shadow 字体的 `DINO CODE` + 圆角状态面板 |
| `tui/Renderer.java` | `toolSummary` 只输出纯文本 | 新增 diff 摘要解析与彩色渲染 |
| `tui/Tui.java` | prompt() 纯 `❯ [模式]`;`再见 🦖` | 新增 `renderInputFrame()`;状态行;去 emoji |
| `tui/Spinner.java` | `⠋ 🦖 thinking…` | `⠋ 小龙思考中…` |
| `tui/DiffView.java` | (不存在) | 新增:统一 diff 计算与渲染工具类 |
| `tool/EditFileTool.java` | 返回 `已修改 path` | 返回带机器可读 diff 标记的摘要 |
| `tool/WriteFileTool.java` | 返回 `已写入 path(N 字节)` | 同上(新建/覆盖两种) |

## 3. 详细设计

### 3.1 Banner(tui/Banner.java)

```text
██████╗ ██╗ ███╗   ██╗ ██████╗  ██████╗ ██████╗ ██████╗ ███████╗
██╔══██╗██║ ████╗  ██║██╔══██╗██╔════╝██╔═══██╗██╔══██╗██╔════╝
██║  ██║██║ ██╔██╗ ██║██║  ██║██║     ██║   ██║██║  ██║█████╗
██████╔╝██║ ██║╚██╗██║██║  ██║██║     ██║   ██║██║  ██║██╔══╝
██╔══██╗██║ ██║ ╚████║██████╔╝╚██████╗╚██████╔╝██████╔╝███████╗
╚═╝  ╚═╝╚═╝ ╚═╝  ╚═══╝╚═════╝  ╚═════╝  ╚═════╝ ╚═════╝ ╚══════╝

╭─────────────────────────────────────────────╮
│ 模型 glm-4.7  会话 新会话  模式 DEFAULT │
╰─────────────────────────────────────────────╯
  Shift+Tab 切换模式 · Tab 补全命令 · /help 帮助
```

- 字体常量 `ANSI_SHADOW_DINO` 以 `List<String>` 硬编码(6 行,每行 64 列,等宽校验放单测)。
- 大字标绿色(`Ansi.GREEN`);状态面板用 `╭─╮│╰─╯` 圆角边框,内容从 `AppConfig` 与 sessionInfo 拼装,面板宽度按最长内容行 +2 自适应。
- 终端宽度 < 68 列时(`terminal.getWidth()`),跳过 figlet 只输出单行 `Dino Code v0.1 · …`,防止折行破碎。

### 3.2 Diff 可视化(DiffView + 工具改造)

**数据流**:`EditFileTool/WriteFileTool.execute()` → 计算 unified-ish diff → 以带标记前缀的摘要文本经 `ToolResult` 返回 → `Agent` 原样放进 `TurnEvent.ToolEnd.summary` → `Renderer.toolSummary` 识别标记渲染彩色行。

**机器可读标记格式**(每行前缀,Renderer 解析后剥离):
```text
@@DIF +2 -1   ← 首行:统计(新增 n 行,删除 m 行)
@@DIF -  <lineno>|<旧行内容>
@@DIF +  <lineno>|<新行内容>
@@DIF =  <lineno>|<上下文行内容>
```
- `-`/`+`/`=` 后跟两位空格;`lineno` 为目标文件中的 1-based 行号;`|` 分隔行号与内容,内容原样保留(含前导空格)。
- EditFileTool 算法:对 `old_string`/`new_string` 各自按 `\n` 切分,替换点定位后,取替换点前后各 2 行作上下文,逐行对齐输出(简单行对齐 diff;不做 LCS,old_string→new_string 通常行数少,足够)。
- WriteFileTool:旧文件存在 → 对新旧全文做**仅删除/新增块**的朴素 diff(逐行比较,首段不同处开始找公共后缀);文件不存在 → `@@DIF +N` 单行统计 + 前 8 行内容预览,标记 `(新文件)`。
- **截断规则**(沿用 Renderer 的 8 行习惯):diff 体最多渲染 8 行,超出时尾行显示 `… 还有 N 行变更`。统计行 `@@DIF +a -b` 永远完整显示。

**渲染效果**(Renderer.toolSummary,识别到 `@@DIF` 时切换 diff 模式):
```text
● Edit src/tui/Banner.java
  ⎿ +2 −1 已应用
  ⎿    13 │   private static final String DINO =
  ⎿ − 13 │     " ▄▄  ▄▄\n" +          ← 红色加粗(首版仅前景色,见 3.5)
  ⎿ + 13 │     "  ▄▄▄▄▄  ▄▄▄▄▄\n" +   ← 绿色加粗
  ⎿    14 │   "( o  o )\n" +
```
- 行号蓝色、`=` 上下文行 DIM、`-` 行 RED、`+` 行 GREEN;删除/新增行加 `Ansi.BOLD` 提高对比。
- 非 Edit/Write 工具或其他纯文本摘要完全不受影响(不含 `@@DIF` 前缀即走旧路径)。
- hook 拦截、权限拒绝、Coordinator 拒绝路径产生的 `ToolEnd` 不含标记,自然走旧渲染,无需改动 Agent.java。

### 3.3 输入框与主界面(Tui.java)

**输入框**(JLine 行内编辑限制下的"三明治"方案):
```text
╭▄▀▄▄▀▄─────────────────────────────╮
│ ❯ 帮我优化 Banner 的恐龙画▌
╰────────────────────────────────────╯    ← 回车提交后打印
```
- `renderInputFrame()`:`reader.readLine(prompt)` 前打印上边框(绿色,宽 = min(终端宽−2, 60)),提交后(readLine 返回、turn() 开始前)打印下边框。
- 上边框左端嵌入鳞片徽标 `▄▀▄▄▀▄`(绿色);边框颜色随权限模式:DEFAULT/ACCEPT_EDITS 绿、PLAN 黄(Ansi.YELLOW 已存在)、BYPASS 红。
- prompt() 本身改为 `│ ❯ ` / `│ ❯ [PLAN] `(与上边框形成视觉连续),颜色规则沿用现逻辑。
- 生成中(spinner 区)不画框;批准菜单、resume 菜单照旧。
- **降级**:终端宽度取不到或 < 30 列时不画框,保持旧版纯 prompt(try-catch 包裹,任何渲染异常不阻断输入)。

**状态行**(每次 turn 结束后在输入框下边框下方输出):
```text
  DEFAULT · glm-4.7 · ↑12,345 ↓3,421 tokens
```
- DIM 色;数据源 `mode.displayName()`、`provider.model()`、累计 `usageIn/usageOut`(千分位)。

**帮助行**:启动 notice 沿用现有文案,补 `/help`(已有,仅统一格式)。

### 3.4 去 emoji

- `Spinner.java:57` → `" ⠋ 小龙思考中…"`(动画帧保留 braille,语义文案中文)。
- `Tui.java:540` → `"再见。"`。
- `Spinner` 类注释同步更新("可安全使用 emoji"的说明一并修正)。

### 3.5 Ansi.java

新增三个常量(供 diff/边框使用):
- `BLUE = ESC + "[34m"`(diff 行号)
- `BOLD` 已存在,复用
- 不引入真彩背景色常量文件级硬编码;若终端支持检测成本高,首版**只用前景色**,背景色留待后续迭代(降级安全优先)。

## 4. 错误处理与降级

| 场景 | 行为 |
|---|---|
| 终端宽度不足(figlet 68 列/边框 30 列) | 跳过对应视觉元素,输出精简版 |
| WriteFileTool 读旧文件失败(不存在等) | 按"新文件"分支输出 |
| DiffView 计算抛异常(理论不抛,防御) | 工具回退旧摘要文案(`已修改 path`),不 fail 工具 |
| Renderer 解析 `@@DIF` 遇到畸形行 | 该行按纯文本 DIM 渲染,不抛异常 |
| JLine 宽度 API 异常 | 输入框整体跳过 |

## 5. 测试计划

- **单元测试**(JUnit,沿用现有 `src/test/java/dinocode/tui/` 风格):
  - `DiffViewTest`:Edit 摘要的行对齐与行号正确性;Write 新文件/覆盖两种统计;畸形输入(空 old/new、多行)。
  - `BannerTest`:6 行等宽 64 列断言;窄终端分支返回单行。
  - `RendererTest`(新增,针对 diff 渲染):含 `@@DIF` 摘要产出含 RED/GREEN/BLUE 码与行号;普通摘要不受影响;>8 行截断提示。
- **端到端**(tmux,按 CLAUDE.md 测试约定):启动 → 看新 Banner → 让它改一个文件 → 验证 diff 行与统计行 → Shift+Tab 切模式看边框变色 → /exit 看退出语。对照本 spec 逐项验收。
- **回归**:现有 `SpinnerTest`、`CommandHandlerTest` 全绿;`grep -rn "🦖" src/` 为空。

## 6. 影响面与不改动清单

- **不改**:`Agent.java`、`TurnEvent`、权限引擎、会话/compact/teams——diff 信息全部在工具层产生、渲染层消费。
- **兼容**:`TurnEvent.ToolEnd.summary` 仍是 String,旧格式与 `@@DIF` 格式并存,Renderer 按前缀分流。
- 存档/回放(session archive)记录的是 ToolResult 原文,含 `@@DIF` 标记的摘要会入档——标记同时是给模型看的结构化信息,无害且有助于模型了解自己改了什么。
