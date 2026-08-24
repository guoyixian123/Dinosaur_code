# Dino Code v0.1 —— 任务拆解

约定：

- Maven 标准目录，包名 `dino`，Java 21
- 每个任务在一次专注会话内可完成；完成后运行 `mvn test` 保持全绿
- 每完成一个任务，把 checklist.md 中对应项勾掉
- 外部文档一律以**实现时的最新官方文档**为准，下列链接仅为起点

---

## T1 项目脚手架 ✅

- **目标**：空工程可编译可运行；引入全部依赖；打通 `mvn` 基本循环
- **影响文件**：`pom.xml`、`src/main/java/dino/Main.java`
- **依赖任务**：无
- **参考**：`spec.md` § 非功能要求。依赖清单：JLine 3（终端交互）、Jackson databind + jackson-dataformat-yaml（JSON/YAML）、JUnit 5（测试）。打包插件（shade）可在 T13 再加

## T2 核心模型：消息、历史、统一流式事件 ✅

- **目标**：定义对话与事件的基础类型，其余模块全部依赖它
- **影响文件**：`src/main/java/dino/core/`（消息、角色、对话历史、流式事件四种：思考增量 / 正文增量 / 结束 / 错误）
- **依赖任务**：T1
- **参考**：`spec.md` § 设计骨架「统一流式事件」「同步阻塞流式 + 虚拟线程」

## T3 配置加载与校验 ✅

- **目标**：读取 YAML；校验字段合法性；环境变量优先的凭据解析；每类配置错误给出对应文案并以非零码退出
- **影响文件**：`src/main/java/dino/config/`、`src/test/java/dino/config/`
- **依赖任务**：T2
- **参考**：`spec.md` § 能力清单 6 / 10 / 14；`checklist.md` § A 全部（**错误文案、默认地址、环境变量名以 checklist 为准**）

## T4 会话持久化 ✅

- **目标**：会话模型（标识、最后活跃时间、消息列表、会话级设置）；JSON 存盘；每轮结束追加存（含中断后的不完整回复）；启动加载最新会话；损坏文件容错
- **影响文件**：`src/main/java/dino/session/`、`src/test/java/dino/session/`
- **依赖任务**：T2
- **参考**：`spec.md` § 能力清单 4 / 5 / 11；`checklist.md` § C（目录位置、文件结构断言、损坏容错行为）

## T5 SSE 行读取公共层 ✅

- **目标**：把响应体按 SSE 语义切成事件块；处理半截行、多事件、空行、流中途断开；协议无关
- **影响文件**：`src/main/java/dino/provider/`（SSE 行读取器）+ 单元测试
- **依赖任务**：T2
- **参考**：SSE 规范（`text/event-stream`）；fixture 放 `src/test/resources/fixtures/`，用例覆盖面见 `checklist.md` § B 最后一条

## T6 OpenAI 适配器 ✅

- **目标**：拼 chat completions 请求体（流式开启）；解析 `data:` 行与结束哨兵；HTTP 错误（认证 / 限流 / 参数错误等）映射为带分类的错误事件
- **影响文件**：`src/main/java/dino/provider/`（OpenAI 适配器）+ 单元测试（fixture 回放）
- **依赖任务**：T5
- **参考**：OpenAI 官方文档 chat completions 流式部分（platform.openai.com，以最新文档为准）；`checklist.md` § B 的 OpenAI fixture 回放项

## T7 Anthropic 适配器 ✅

- **目标**：拼 Messages 请求体（最大输出参数为协议强制项；thinking 开启时附加思考参数并满足其参数约束）；解析 `event:` + `data:` 事件对，区分思考增量与正文增量；认证与版本请求头
- **影响文件**：`src/main/java/dino/provider/`（Anthropic 适配器）+ 单元测试（fixture 回放）
- **依赖任务**：T5
- **参考**：Anthropic 官方文档 Messages 流式与 extended thinking 部分（docs.anthropic.com，以最新文档为准）；`checklist.md` § B / § F

## T8 Provider 工厂 ✅

- **目标**：协议标识 → 适配器映射；后端地址缺省按协议解析、配置可覆盖；统一异常归类
- **影响文件**：`src/main/java/dino/provider/`（工厂）+ 单元测试
- **依赖任务**：T6、T7
- **参考**：`spec.md` § 能力清单 6 / 7 / 8；`checklist.md` § A 中 base_url 相关项

## T9 TUI 渲染与交互循环 ✅

- **目标**：JLine 输入循环（历史 / 行编辑）；流式逐字输出；thinking 暗色区分；每轮结束暗色显示 token 用量；错误红字；首字到达前的思考指示动画；启动 banner（绿色恐龙 ASCII + 状态行）；绿色提示符
- **影响文件**：`src/main/java/dino/tui/`
- **依赖任务**：T2
- **参考**：`spec.md` § 能力清单 1 / 2 / 9 / 15；`checklist.md` § B / § H（**颜色、提示符形状、banner 内容以 checklist 为准**）

## T10 离线流式验证环境 ✅

- **目标**：用 JDK 自带 HTTP server 搭建 mock 端点，回放 fixture；离线跑通「配置 → Provider → 事件流 → 渲染」全链路；覆盖中断语义（中途掐断，已流出内容保留）与 base_url 指向
- **影响文件**：`src/test/java/dino/`（离线流式测试、mock 端点）
- **依赖任务**：T8、T9（渲染部分用真实渲染器验证）
- **参考**：`spec.md` § 非功能要求「可离线验证流式链路」；`checklist.md` § A / § B / § E 中标注「mock」的项

## T11 命令与中断 ✅

- **目标**：`/exit` `/new` `/help` `/tokens`（四档位 + 自定义数值，调整写入会话级设置）；未知命令与非法参数的提示；Ctrl+C 分时机语义——生成中中断本轮（已流出内容保留，为空则不记）、输入行非空清空当前行、输入行为空退出程序
- **影响文件**：`src/main/java/dino/tui/`（命令分发、中断处理）、会话模型增加会话级设置字段
- **依赖任务**：T9、T4
- **参考**：`spec.md` § 能力清单 11 / 12；`checklist.md` § D / § E（**档位对应数值、默认值、提示文案以 checklist 为准**）

## T12 接入主流程 ✅

- **目标**：`Main` 组装：加载配置（出错 → 按文案退出）→ 建 Provider → 恢复会话 → 进入交互循环；各模块端到端串通，接 mock 端点可完整使用
- **影响文件**：`src/main/java/dino/Main.java` 及各模块接线处
- **依赖任务**：T3、T4、T8、T9、T10、T11
- **参考**：`spec.md` § 设计骨架；`checklist.md` § A 启动行为各项

## T13 端到端验证 🔄（打包完成，真实 key 验收移交用户）

- **目标**：逐条执行 checklist.md；**真实 Anthropic key 与真实 OpenAI key 各至少完整跑一次**；产出 fat jar 与启动脚本 `dino`
- **影响文件**：`checklist.md`（勾选）、`pom.xml`（打包配置）、`dino`（启动脚本）
- **依赖任务**：T12
- **参考**：`checklist.md` 全部，尤其 § I 端到端验收三项
