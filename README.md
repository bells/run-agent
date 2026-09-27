# RunAgent v0.3

RunAgent 是 Watson 的个人跑步 AI 后端与 Java / Spring AI 学习项目。它与 [Watson Running](https://github.com/bells/watson-running) 独立构建和部署。

## 能力与边界

- `POST /api/chat`：普通 Chat 和简单 Tool-aware Chat。
- `GET /api/chat/stream`：UTF-8 SSE Chat，保留 `token` 与安全 `error` 事件。
- `POST /api/intent`：Structured Output，不注册 Running Tools。
- `POST /api/agent`：多步 Tool Calling，返回 `executionId`、实际执行的 `toolCallCount` 和最终 `content`。

Tool Calling 使模型能够请求 Java 工具；Agent Loop 使模型在看到一次工具结果后继续决定是否调用工具，以及调用哪个工具。Tool Calling 是 Agent 的基础能力之一，但不等于完整 Agent。v0.3 每个请求独立运行，没有 Memory、RAG、数据库、多 Agent 或对话历史。下一阶段 v0.4 才研究 Memory。

```text
User → POST /api/agent → RunAgentService → ChatClient → ToolCallingAdvisor → LLM
                                                        ↑                  ↓
                                                        └── Observation ← RunningTools
                                                                            ↓
                                                                  RunningDataService
                                                                            ↓
                                                                     activities.json
                         no more tool calls → Final Answer
```

比较两个时间段时，模型应分别调用 `getRunningSummary` 两次；需要个别训练时再调用 `getRecentRuns`。不提供 `compareRunningPeriods`，以便观察根据前一 Observation 作出的下一步决定。完整机制见 [Agent Loop](docs/agent-loop.md)；v0.2 的底层协议学习实验见 [Tool Calling Flow](docs/tool-calling-flow.md)。

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
./gradlew bootRun
```

`DEEPSEEK_MODEL` 默认为 `deepseek-v4-pro`，`DEEPSEEK_BASE_URL` 默认指向 DeepSeek。请使用被 Git 忽略的本地配置存放真实密钥与路径。数据文件只读，每次工具执行重新加载；文件不可用时不会捏造个人统计。

`spring.ai.tools.limits` 在 Spring AI 2.0.1 中绑定：每个 Tool 每轮最多 **4** 次、所有 Tool 合计最多 **8** 次，超限行为 `THROW`。可通过 `AGENT_MAX_CALLS_PER_TOOL` 和 `AGENT_MAX_TOTAL_TOOL_CALLS` 覆盖。Advisor 将超限转成 `toolCallLimitExceeded` finish reason；服务将它映射成安全的 `AGENT_TOOL_LIMIT` 错误。Tool call limits are safety boundaries, not business retry policies.

`/api/agent` 默认请求级超时 **30s**，可用 `AGENT_TIMEOUT` 覆盖，例如 `45s`。这是应用层 deadline 与尽力取消；阻塞的 Provider HTTP 调用不保证立即中断。超时返回 `AGENT_TIMEOUT`。每次 Agent 请求生成 UUID，工具日志记录 `agentExecutionId`、`step`、工具名、状态和耗时；step 只是 Tool execution sequence number。Agent trace observes actions, not hidden model reasoning。日志不记录用户消息、完整 Prompt、密钥或轨迹。

## API 示例

```bash
curl -X POST http://localhost:8080/api/agent \
  -H 'Content-Type: application/json' \
  -d '{"message":"比较我最近30天和之前30天的跑步情况，再结合最近5次跑步分析训练状态。"}'
```

```json
{"executionId":"<uuid>","toolCallCount":3,"content":"..."}
```

实际 Tool 顺序由模型决定，示例次数不保证。通用问题如“什么是节奏跑？”无需 Tool。`/api/chat` 继续返回 `{"content":"..."}`；`/api/intent` 继续返回 `RunningIntent`。

```bash
./gradlew test
./gradlew build
```

自动测试使用本地合成数据与 stub 模型，不调用真实 DeepSeek。真实多轮效果需要在有 Key 和只读 Running Data 时手动观察 `executionId`、Tool 顺序、次数和最终回答。跨仓库边界见 [Watson Running Integration Boundary](docs/watson-running-integration.md)。
