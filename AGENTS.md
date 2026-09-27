# RunAgent Agent Guide

本文件适用于整个 `run-agent` 仓库。所有 AI Agent、代码助手和自动化工具开始任务前都应先阅读本文件，再根据任务读取相关源码、测试和文档。

## 项目定位

- RunAgent 是 Watson 的个人跑步 AI 后端，也是按阶段学习 LLM 应用工程的 Java 项目。
- 当前仓库：<https://github.com/bells/run-agent>。
- 前端项目是独立仓库 [Watson Running](https://github.com/bells/watson-running)，本地通常位于相邻目录 `../watson-running`。
- `bells/running_page` 是 Watson Running 独立迁移前的历史仓库，不是 RunAgent 当前应对接的前端目标。
- 两个项目保持独立仓库、独立构建和独立部署。除非用户明确要求跨仓库修改，否则只修改当前仓库。

```text
watson-running
React / TypeScript / maps / charts / RunAgent UI
        |
        | HTTPS REST / SSE
        v
run-agent
Java 21 / Spring Boot / Spring AI
```

## 当前阶段

当前版本是 v0.3，已经实现：

- `POST /api/chat`：普通 Chat。
- `GET /api/chat/stream`：UTF-8 SSE Streaming Chat。
- `POST /api/intent`：`RunningIntent` Structured Output。
- `getRunningSummary`：通过配置的本地 `activities.json` 只读统计真实跑步数据；普通与 Streaming Chat 可使用。
- `POST /api/agent`：基于 Spring AI ToolCallingAdvisor 的多步 Tool Calling；新增 `getRecentRuns`、`getPersonalBest`、调用限制、请求超时和执行 trace。

v0.3 不实现自定义 Agent Loop、Memory、数据库、Embedding、RAG、MCP、Workflow、LangGraph、Multi-Agent、复杂认证或完整 Observability。后续能力必须按版本目标逐步加入。

## 已验证技术基线

- Java 21。
- Gradle Wrapper 9.7.1。
- Spring Boot 4.1.1。
- Spring AI 2.0.1。
- Spring WebFlux / Reactor、Bean Validation、JUnit 5。

使用 Gradle Wrapper，不要引入 Maven 构建或提交本机 Gradle/JDK 文件：

```bash
./gradlew test
./gradlew build
./gradlew bootRun
```

修改版本前必须确认 Spring Boot、Spring AI、Gradle 和 Java 的官方兼容关系，并以实际编译和测试结果为准，不照搬旧版 API 示例。

## 架构边界

请求链路保持简单：

```text
Controller -> RunAgentService -> ChatClient -> LLM
```

- `controller/` 只处理 HTTP、参数校验、响应映射和协议层行为，不直接堆放 Spring AI 调用或未来工具编排。
- `agent/RunAgentService` 集中管理 ChatClient 调用，是后续 Tool Calling 和 Agent Loop 的首选扩展点。
- `model/` 保存明确的请求、响应和跨边界类型，优先使用 Java record 和 enum。
- `prompt/PromptCatalog` 加载 `src/main/resources/prompts/` 下的提示词。不要把大段 Prompt 散落在 Controller 或 Service 方法中。
- `exception/` 提供小而明确的异常和安全的 HTTP 错误响应，不建立庞大的通用基础框架。
- 不要仅为“分层”创建没有真实替换需求的 interface + impl。

## Java 与 Spring AI 约定

- 使用 Java 21 原生能力，DTO 优先 record；不引入 Lombok，除非它解决了明确且反复出现的问题。
- 使用构造器注入，类和方法保持单一职责。
- 所有模型调用失败必须转换为明确的领域异常，不能向客户端返回完整堆栈或供应商响应细节。
- Structured Output 优先使用 Spring AI Java 类型/Schema 映射，例如 `entity(RunningIntent.class)`；不能只依赖“请返回 JSON”的 Prompt。
- 模型输出是不可信输入。映射后仍需验证日期范围、枚举、必填字段和业务约束。
- 用户询问真实个人跑步统计时，必须依赖 Running Tool 的结果；数据不可用时明确说明，不得捏造历史、里程、配速或 PB。
- 真实模型验证是可选 smoke test，单元测试和常规构建不得依赖外部 API、网络或付费调用。

## WebFlux 与 Streaming 约定

- 不在 Netty event loop 上执行阻塞式 `ChatClient.call()`；同步调用继续通过 `boundedElastic` 或后续明确的阻塞边界执行。
- Streaming 保持 Reactor `Flux` 链路，使用 `text/event-stream;charset=UTF-8`。
- 保留客户端取消信号的传播，不在流外创建无法取消的后台任务。
- 流开始后的失败返回安全且稳定的 SSE error event，不泄漏异常详情。
- 当前不增加复杂 Retry 或自定义 Backpressure。以后增加时必须限定次数、超时，并说明幂等性。

## API 与跨仓库契约

- 每个跨仓库 payload 都要在 Java 和 TypeScript 中显式定义并保持对称，禁止以 `Map<String, Object>` 代替稳定契约。
- 当前 `/api/*` 是学习阶段已有接口。Watson Running 正式接入时应设计版本化 `/api/v1/*` 合约，不要无说明地破坏现有接口。
- SSE event name、data 结构和终止状态必须稳定并可测试。未来需要重连时增加 request/conversation id 和可判定的 terminal event。
- 浏览器跨域策略、服务地址和认证必须显式配置；不要为图方便默认开放任意 Origin。
- 详细边界见 `docs/watson-running-integration.md`。

## Watson Running 数据与隐私边界

- `watson-running` 负责 React/TypeScript UI、地图、图表、统计、历史展示及现有静态数据生成流程。
- RunAgent 负责 AI 模型接入、服务端分析以及未来的 Tool、Memory、RAG、MCP、Evaluation 和服务端授权。
- 不要把 Java/Spring AI、模型密钥、Memory 或 Agent 业务逻辑复制到前端仓库。
- v0.3 仅在显式配置 `RUNNING_DATA_PATH` 后只读访问生成后的 `activities.json`，不修改它。不要直接共享或修改 SQLite、GPX、TCX、FIT 或 SVG 资产；后续正式集成应评审只读契约或受控导出。
- 跑步轨迹包含精确位置。原始轨迹、起终点、平台凭据和仓库 Secrets 默认不得发送给浏览器或 LLM。
- 跨仓库任务先检查两个工作树，并分别报告修改和验证结果；不得顺手改动历史仓库 `running_page`。

## 配置与安全

- 当前唯一模型供应商是 DeepSeek，运行时配置使用 `DEEPSEEK_API_KEY`、`DEEPSEEK_MODEL` 和 `DEEPSEEK_BASE_URL`。
- API Key、Authorization Header、平台密码、refresh token、真实个人数据不得出现在源码、测试、日志、README、提交记录或命令输出中。
- `application-local.yml`、`.env*` 等本地配置必须保持忽略；示例文件只能使用占位符或环境变量引用。
- 日志可以记录请求类型、长度、耗时、状态和非敏感标识，不记录完整用户消息、模型密钥或原始轨迹。

## 测试与验证

- 修改业务代码至少运行 `./gradlew test`。
- 修改依赖、配置、启动流程或打包行为运行 `./gradlew build`。
- Controller 测试覆盖校验、状态码和安全错误体；Service 测试使用 stub/mock ChatModel，不调用真实 DeepSeek API。
- Streaming 修改需测试正常片段、错误事件和取消/结束行为；自动测试无法证明真实供应商流式语义时要明确说明。
- 只修改 Markdown 等文档时至少运行 `git diff --check`。
- 不得用“Context 能启动”代替真实的 API、SSE 或供应商兼容性验证结论。

## Git 与交付

- 开始修改前检查 `git status --short --branch`，保留用户现有改动。
- 不使用 `git reset --hard`、`git checkout --` 等破坏性命令清理工作树。
- 提交前只暂存本任务文件，检查 working tree、staged diff 和 `git diff --cached --check`。
- 用户说“提交代码”时，重跑相关检查、创建本地提交并报告 commit hash。
- 除非用户明确要求，不要 push、打 tag、创建 release 或修改 `watson-running`。

## Roadmap 扩展顺序

```text
v0.3 Agent Loop (current)
v0.4 Memory
v0.5 RAG
v0.6 MCP
v0.7 Evaluation / Observability
v0.8 Workflow / Multi-Agent
```

每一阶段都应先确认学习目标和最小可验证闭环，再增加依赖与抽象。
