# RunAgent v0.4

RunAgent 是 Watson 的个人跑步 AI 后端与 Java / Spring AI 学习项目。它与 [Watson Running](https://github.com/bells/watson-running) 独立构建和部署。

## 能力与边界

- `POST /api/chat`：普通 Chat 和简单 Tool-aware Chat。
- `GET /api/chat/stream`：UTF-8 SSE Chat，保留 `token` 与安全 `error` 事件。
- `POST /api/intent`：Structured Output，不注册 Running Tools。
- `POST /api/agent`：多步 Tool Calling + 有界 Conversation Memory，返回 `conversationId`、`executionId`、实际执行的 `toolCallCount` 和最终 `content`。

Tool Calling 使模型能够请求 Java 工具；v0.3 Agent Loop 在一次请求内根据 Tool 结果继续行动。v0.4 让 `/api/agent` 跨请求恢复有限对话上下文。`/api/chat`、`/api/chat/stream` 和 `/api/intent` 仍是无状态接口。

```text
Client conversationId → POST /api/agent → RunAgentService
                                              ↓
                            MessageChatMemoryAdvisor ⇄ MessageWindowChatMemory
                                              ↓                  ↓
                                         ChatClient     InMemoryChatMemoryRepository
                                              ↓
                                     ToolCallingAdvisor ⇄ LLM
                                              ↓
                              RunningTools → RunningDataService → activities.json
                                              ↓
                                          Final Answer
```

比较两个时间段时，模型应分别调用 `getRunningSummary` 两次；需要个别训练时再调用 `getRecentRuns`。不提供 `compareRunningPeriods`，以便观察根据前一 Observation 作出的下一步决定。完整机制见 [Agent Loop](docs/agent-loop.md)；v0.2 的底层协议学习实验见 [Tool Calling Flow](docs/tool-calling-flow.md)。

## Conversation Memory

`AgentRequest` 是 `{ "conversationId": "run-session-1", "message": "..." }`。`conversationId` 可省略或设为 `null`，服务端会生成 UUID 并在响应中返回。客户端保存它，下一轮原样传回。显式 ID 最长 100 字符，只接受英文字母、数字、`-` 和 `_`；它是 Memory 命名空间，不是认证或授权。

`MessageChatMemoryAdvisor` 只在 `/api/agent` 的调用中附加，按 `ChatMemory.CONVERSATION_ID` 从 `MessageWindowChatMemory` 恢复消息并保存本轮 User / 最终 Assistant 消息。ToolCallingAdvisor 在其内层完成本次多步工具循环；历史回答可帮助理解“那段时间”，当前问题需要的个人统计仍须重新调用 Running Tools。自动 Tool Calling 的中间 Tool Call / Tool Result 不会被当作完整 Agent transcript 存入 Memory。

| 概念 | 含义 |
| --- | --- |
| Context Window | 当前一次模型请求真正发送的 Token，可能包含系统提示、Memory、当前问题和本次 Tool 结果 |
| Chat Memory | 应用在模型外维护、按 conversationId 取回并重新注入的有界上下文 |
| Chat History | 全部对话的长期完整记录；v0.4 不保存 |
| RAG | 按当前查询从外部知识源检索相关内容；v0.4 不实现 |

`conversationId` 跨多个请求；每次请求另有新的 `executionId`，只标识该次执行 trace。默认 `maxMessages=20`，通过 `AGENT_MEMORY_MAX_MESSAGES` 调整，允许 1–200。窗口超限会移除旧的完整对话回合；它并非永久归档。`InMemoryChatMemoryRepository` 在应用重启后丢失内容，多实例之间也不共享。完整机制、边界和实验见 [Conversation Memory](docs/memory.md)。

## 三个 Running Tools

| Tool | 模型参数 | Java 返回及计算 |
| --- | --- | --- |
| `getRunningSummary` | `startDate`、`endDate`，两端包含 | Run 次数、总公里、总秒数 |
| `getRecentRuns` | 同上，及 `limit` 1–20 | 按 `start_date_local` 降序的精简活动：日期、公里、秒数、秒/公里配速 |
| `getPersonalBest` | `distanceType`：`FIVE_K`、`TEN_K`、`HALF_MARATHON`、`MARATHON` | 标准距离附近整次活动中平均配速最快的一次 |

日期使用 ISO `yyyy-MM-dd`，查询区间最多 366 个自然日。只读取 `type=Run`。Java 负责过滤、排序、米转公里、时长解析与配速计算；模型负责决定、比较和解释。距离为 0 时配速为 `null`。返回模型的类型仅包含必要字段；GPS、经纬度、起终点与 Polyline 不进入 Tool Result。

`getPersonalBest` 使用目标距离 **±5%** 的集中容差，按整次活动的未舍入平均配速选最快，再返回四舍五入的秒/公里配速。它是 **recorded activity-level estimate**：`approximate=true`、`calculationBasis=WHOLE_ACTIVITY_DISTANCE_MATCH`。JSON 没有 split、lap 或 segment，无法从 10.5 km 活动中计算精确 10K split PB。没有匹配时返回 `calculationBasis=NO_MATCHING_ACTIVITY` 和空日期，模型应说明没有对应记录。

## 配置与启动

技术基线：Java 21、Gradle Wrapper 9.7.1、Spring Boot 4.1.1、Spring AI 2.0.1、WebFlux / Reactor、Bean Validation 和 JUnit 5。

```bash
export DEEPSEEK_API_KEY="your-api-key"
export RUNNING_DATA_PATH=/absolute/path/to/watson-running/src/static/activities.json
export AGENT_MEMORY_MAX_MESSAGES=20
./gradlew bootRun
```

`DEEPSEEK_MODEL` 默认为 `deepseek-v4-pro`，`DEEPSEEK_BASE_URL` 默认指向 DeepSeek。请使用被 Git 忽略的本地配置存放真实密钥与路径。数据文件只读，每次工具执行重新加载；文件不可用时不会捏造个人统计。

`spring.ai.tools.limits` 在 Spring AI 2.0.1 中绑定：每个 Tool 每轮最多 **4** 次、所有 Tool 合计最多 **8** 次，超限行为 `THROW`。可通过 `AGENT_MAX_CALLS_PER_TOOL` 和 `AGENT_MAX_TOTAL_TOOL_CALLS` 覆盖。Advisor 将超限转成 `toolCallLimitExceeded` finish reason；服务将它映射成安全的 `AGENT_TOOL_LIMIT` 错误。Tool call limits are safety boundaries, not business retry policies.

`/api/agent` 默认请求级超时 **30s**，可用 `AGENT_TIMEOUT` 覆盖，例如 `45s`。这是应用层 deadline 与尽力取消；阻塞的 Provider HTTP 调用不保证立即中断。超时返回 `AGENT_TIMEOUT`。每次 Agent 请求生成 UUID `executionId`，日志记录安全的 `conversationId`、消息数量、工具 step、状态和耗时；Provider 返回 Usage 时还记录 token 数。step 只是 Tool execution sequence number。Agent trace observes actions, not hidden model reasoning。日志不记录用户消息、完整 Prompt、Memory 全文、密钥或轨迹。

## API 示例

```bash
curl -X POST http://localhost:8080/api/agent \
  -H 'Content-Type: application/json' \
  -d '{"message":"分析我最近30天的跑步情况。"}'
```

```json
{"conversationId":"<uuid>","executionId":"<uuid>","toolCallCount":1,"content":"..."}
```

第二轮传 `{"conversationId":"<上次返回的 uuid>","message":"那跟之前30天比呢？"}`，第三轮继续传同一个 ID 问“最近几次跑步有什么变化？”。两轮的 `executionId` 各不相同。实际 Tool 顺序由模型决定，示例次数不保证。通用问题如“什么是节奏跑？”无需 Tool。`/api/chat` 继续返回 `{"content":"..."}`；`/api/intent` 继续返回 `RunningIntent`。

```bash
./gradlew test
./gradlew build
```

自动测试使用本地合成数据与 stub 模型，不调用真实 DeepSeek。真实多轮语言理解效果需要在有 Key 和只读 Running Data 时手动观察。跨仓库边界见 [Watson Running Integration Boundary](docs/watson-running-integration.md)。
