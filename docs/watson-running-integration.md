# Watson Running Integration Boundary

## 项目关系

[Watson Running](https://github.com/bells/watson-running) 是 RunAgent 对应的前端和跑步数据展示项目，生产站点为 <https://run.watsonzhu.cn/>。RunAgent 是独立的 Java AI 后端。

```text
watson-running                           run-agent
React / TypeScript                      Java 21 / Spring Boot
地图、图表、统计、历史展示       REST    Spring AI / ChatClient
RunAgent 对话与分析界面        <----->   AI 分析与服务端安全边界
浏览器流式交互                  SSE     Running Tool / 未来 Memory / RAG / MCP
```

两个项目不是 monorepo，也不共享构建系统或运行时密钥。

## 当前状态

RunAgent v0.2 已提供学习用途的未版本化 API：

| API | 协议 | 当前作用 |
| --- | --- | --- |
| `POST /api/chat` | JSON | 普通跑步问答，可调用 Running Tool |
| `GET /api/chat/stream` | UTF-8 SSE | 流式跑步问答，可调用 Running Tool；事件名为 `token` / `error` |
| `POST /api/intent` | JSON | 把自然语言映射为 `RunningIntent` |

RunAgent 的 Running Tool 只在服务端配置 `RUNNING_DATA_PATH` 后读取生成后的 `activities.json`，当前仍属本地学习阶段。正式前端接入和部署需要单独设计版本化契约。

## 职责划分

### Watson Running

- React/TypeScript 页面、响应式交互、地图、图表、统计和历史展示。
- RunAgent 对话入口、流式内容呈现、取消、重连和错误反馈。
- REST/SSE 对应的 TypeScript 类型与浏览器请求生命周期。
- 现有 Python 同步、清洗和静态资产生成流程。

### RunAgent

- Java 21 / Spring Boot 服务端运行时。
- Spring AI 模型接入和 System Prompt。
- 请求校验、服务端错误语义和敏感配置保护。
- 当前阶段的只读 Tool Calling，以及后续 Agent Loop、Memory、RAG、MCP 和 Evaluation。

模型密钥、平台凭据、Memory 和 Agent 编排只能存在于 RunAgent 服务端，不能打包进前端。

## 正式接入前的契约要求

当前 `/api/*` 接口可以继续用于 v0.2 学习和 smoke test。正式让 Watson Running 调用时，应先定义版本化 `/api/v1/*` 契约，并完成以下事项：

1. Java record 与 TypeScript interface/union 一一对应。
2. 给 SSE 定义稳定的 event name、typed data、request id 和明确的 complete/error/cancelled 终态。
3. 明确超时、取消、有限重试、重复事件与断线重连语义。
4. 使用结构化错误码，前端不得解析异常字符串判断业务状态。
5. 通过环境变量配置 RunAgent base URL；本地和生产环境不能硬编码为同一个地址。
6. 按实际部署域名配置受限 CORS；默认不开放 `*`。
7. 为两仓库维护相同的 JSON/SSE contract fixtures 或契约测试。

在出现经过验证的双向实时需求前，保持 REST + SSE，不提前增加 WebSocket。

## 跑步数据与隐私

Watson Running 的数据生成流程会产生 SQLite、GPX/TCX/FIT、`activities.json` 和 SVG 等相互关联的资产，其中轨迹可能暴露精确位置。

当前及未来 Running Data Tool 应遵循：

- 默认只读；v0.2 使用显式配置的本地生成文件，正式集成再评审 API 或受控导出，不直接修改前端仓库数据库。
- 只向模型发送完成问题所需的最少字段和最小时间范围。
- 在进入 RunAgent 或 LLM 前复用/强化 Watson Running 的轨迹隐私过滤。
- 默认不发送原始 GPX、精确起终点、平台凭据、仓库 Secret 或未脱敏备注。
- 对里程、配速、时间、PB 和聚合结果增加确定性计算与测试，不能完全依赖模型心算。
- 所有 Tool 输出都视为不可信输入，进入 Prompt 前进行大小、类型和权限校验。

## 不属于当前接入范围

- 把两个仓库合并成 monorepo。
- 把 Python 数据同步管道迁移到 Java。
- 让浏览器或模型直接读取 SQLite/原始轨迹。
- 从 RunAgent 写入、删除或批量修复活动记录。
- 在 v0.2 中提前实现更多 Tool、Memory、RAG、MCP 或复杂认证。
