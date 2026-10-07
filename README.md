# RunAgent v0.5

RunAgent 是 Watson 的个人跑步 AI 后端与 Java / Spring AI 学习项目。它与 [Watson Running](https://github.com/bells/watson-running) 独立构建和部署。

## 能力与边界

- `POST /api/chat`：普通 Chat 和简单 Tool-aware Chat。
- `GET /api/chat/stream`：UTF-8 SSE Chat，保留 `token` 与安全 `error` 事件。
- `POST /api/intent`：Structured Output，不注册 Running Tools。
- `POST /api/knowledge/ask`：无状态的跑步书籍 RAG 问答；默认关闭，使用前需启用本地知识库。
- `POST /api/knowledge/search`：仅 local profile 提供检索调试，返回来源、分数及最多 200 字的 preview。
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
| RAG | 按当前查询从外部知识源检索相关内容；v0.5 使用独立 Knowledge API |

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
export AI_GATEWAY_BASE_URL="https://api.deepseek.com"
export AI_GATEWAY_API_KEY="your-api-key"
export AI_GATEWAY_MODEL="deepseek-v4-pro"
export RUNNING_DATA_PATH=/absolute/path/to/watson-running/src/static/activities.json
export AGENT_MEMORY_MAX_MESSAGES=20
./gradlew bootRun
```

服务端使用 Spring AI OpenAI Chat 客户端连接兼容的 Chat Completions 端点。切换提供方只需在启动前更改以下三项；不要将 API Key 放入前端或提交到仓库。

| 提供方 | `AI_GATEWAY_BASE_URL` | `AI_GATEWAY_MODEL` | `AI_GATEWAY_API_KEY` |
| --- | --- | --- | --- |
| DeepSeek | `https://api.deepseek.com` | 账户可用的 DeepSeek Chat 模型，例如 `deepseek-v4-pro` | DeepSeek Key |
| OpenRouter | `https://openrouter.ai/api/v1` | OpenRouter 模型 ID，例如 `deepseek/deepseek-chat` | OpenRouter Key |
| 本地 Ollama | `http://localhost:11434/v1` | 已通过 `ollama pull` 下载、支持所需能力的模型名 | 非空占位值，例如 `ollama` |

`AI_GATEWAY_BASE_URL` 是 API 根地址，不含 `/chat/completions`；OpenAI 客户端会添加该路径。DeepSeek 的根地址默认是 `https://api.deepseek.com`，模型默认 `deepseek-v4-pro`。若没有设置新的 `AI_GATEWAY_*` 变量，旧的 `DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL` 以及被 Git 忽略的 `application-local.yml` 中 `spring.ai.deepseek.*` 值仍可作为回退。新配置优先；本地 profile 也可直接使用 `spring.ai.openai.api-key`、`spring.ai.openai.base-url`、`spring.ai.openai.chat.model`。可将 [application-local.yml.example](application-local.yml.example) 复制到 `src/main/resources/application-local.yml`，再选择其中一个提供方；真实密钥只放在被 Git 忽略的本地配置或环境变量中。

Ollama 需要本地服务已启动且模型已下载；OpenAI 兼容端点通常忽略 API Key，但客户端仍要求非空值。三种后端的 Chat Completions 兼容程度会因模型和服务版本而异，尤其是 Tool Calling、Streaming 和 Structured Output；切换后应分别验证所需接口。数据文件只读，每次工具执行重新加载；文件不可用时不会捏造个人统计。

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

自动测试使用本地合成数据、stub 模型和本地假网关，不调用真实提供方。真实多轮语言理解效果需要在所选模型可用且只读 Running Data 已配置时手动观察。跨仓库边界见 [Watson Running Integration Boundary](docs/watson-running-integration.md)。

## RAG Running Knowledge Base

v0.5 增加 Document → Chunk → Embedding → SimpleVectorStore → Retrieval → QuestionAnswerAdvisor → Answer 闭环。默认 `RAG_ENABLED=false`；测试和构建不需要 Ollama 或电子书。

```text
                           RunAgent
             ┌────────────────┼──────────────────┐
             ▼                ▼                  ▼
           Memory        Running Tools           RAG
             │                │                  │
      Conversation      activities.json      SimpleVectorStore
         Context          Java 精确聚合           │
                                           四本本地跑步书籍
```

| 能力 | 数据 | 示例 | 接口 |
| --- | --- | --- | --- |
| Memory | 有界 conversation context | “那跟上个月比呢？” | `/api/agent` |
| Tool | 真实结构化跑步数据 | “今年跑了多少公里？” | Chat / Agent 的 Running Tools |
| RAG | 外部非结构化书籍 | “T 配速训练目的是什么？” | `/api/knowledge/ask` |

Structured Data ≠ RAG Data。`activities.json` 的统计仍由 Java 确定性计算；Conversation Memory 不做 Embedding；RAG Advisor 不全局加入 ChatClient 或 `/api/agent`。

本地目录可指向一本书、一个文件，或包含四个书籍子目录的集合。每个目录按 **EPUB → TXT → MD** 选择一个版本，同格式按文件名排序；免责声明和隐藏文件不索引。本机四本书都使用 EPUB，因此只增加 Tika Reader，未添加 PDF / MOBI / AZW3 Reader。详见 [RAG 学习与实验](docs/rag.md)。

```bash
ollama serve  # 已启动时无需重复运行
ollama list
ollama pull bge-m3  # 首次准备；应用 pull-model-strategy=never
export RAG_ENABLED=true
export RAG_SOURCE_PATH="/absolute/path/to/run-book"
export RAG_VECTOR_STORE_PATH=".run-agent/rag/vector-store.json"
export RAG_OLLAMA_BASE_URL="http://localhost:11434"
export RAG_EMBEDDING_MODEL="bge-m3"
export RAG_REINDEX=true
./gradlew bootRun
```

Chat 仍独立使用 `AI_GATEWAY_BASE_URL`、`AI_GATEWAY_API_KEY`、`AI_GATEWAY_MODEL`。可使用 DeepSeek / OpenRouter，也可全部留在本机：

```bash
export AI_GATEWAY_BASE_URL="http://localhost:11434/v1"
export AI_GATEWAY_API_KEY="ollama"
export AI_GATEWAY_MODEL="qwen3:8b"
```

首次观察 `EXTRACTED`、`CHUNKED`、`VALIDATED`、`INDEXED`。停止后设 `RAG_REINDEX=false` 重启，应看到 `LOADED`，不会重新 Embedding 全书。索引或模型不可用时只停用 Knowledge 模块，接口返回安全 `503 KNOWLEDGE_UNAVAILABLE`，已有能力不受影响。加载会检查模型/端点/切块参数指纹及索引 checksum。**Changing the embedding model requires rebuilding the vector index.** 修改书籍集合或正文也需主动 Reindex。

```bash
curl -X POST http://localhost:8080/api/knowledge/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"丹尼尔斯训练法中T配速训练的主要目的是什么？"}'
```

响应为 `{ "queryId": "<uuid>", "content": "..." }`。无召回结果时直接返回证据不足，不调用 Chat Model；召回内容不支持问题时由 Prompt 要求拒绝归因。语义分数并不能保证正确，仍须人工核对。

检索 preview 只在 `--spring.profiles.active=local` 启用：

```bash
curl -X POST http://localhost:8080/api/knowledge/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"阈值配速训练有什么作用？"}'
```

当前没有认证，本地个人实验请绑定 `--server.address=127.0.0.1`。`local` 是配置环境，不是认证。书籍、提取正文、完整 Chunk、向量 JSON 和 manifest 全部留在 `.run-agent/` 等被忽略目录；自定义索引路径也必须忽略。不要将书籍放到 Git。日志只有来源标题、数量、queryId、rank、score 和耗时，不输出问题、正文或向量。若 Chat 指向远程提供方，召回片段会作为 Prompt Context 发给该提供方；本地 Qwen 配置可让本实验的书籍内容全部留在本机。

`SimpleVectorStore` 使用内存及余弦相似度、本地 JSON 持久化，**Not for production.** 默认 chunkSize=800、TopK=5、threshold=0.5 是学习起点，尚不代表最优。`RAG_TIMEOUT=180s` 是请求 deadline 和尽力取消，阻塞 Provider HTTP 不保证立即中断。当前无显式 Chunk Overlap、Hybrid Search、Reranker、RAG Tool 或 Agentic RAG。

## Roadmap

| 版本 | 学习阶段 |
| --- | --- |
| v0.1 | LLM Basics |
| v0.2 | Tool Calling |
| v0.3 | Agent Loop |
| v0.4 | Memory |
| v0.5 | RAG（当前） |
| v0.6 | MCP |
| v0.7 | Workflow / LangGraph |
| v0.8 | Evaluation / Observability |
| v0.9 | Multi-Agent |
