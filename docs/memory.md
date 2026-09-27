# RunAgent v0.4 Conversation Memory

## 为什么应用需要 Memory

LLM 本身不保留上次 HTTP 请求的状态。v0.3 的 ToolCallingAdvisor 可以在**一次请求内**把 Tool Result 交回模型并继续行动；下一次请求仍是新调用。v0.4 由应用保存有限消息，按 `conversationId` 取回，再通过 `MessageChatMemoryAdvisor` 注入当前模型请求。因此连续性来自应用，并非模型永久记住了用户。

```text
AgentRequest(conversationId, message)
  → RunAgentService：生成或沿用 conversationId，创建新的 executionId
  → MessageChatMemoryAdvisor：从 ChatMemory 取回旧消息并加入当前 Prompt
  → ToolCallingAdvisor：在本次请求内运行 LLM ⇄ Running Tools 循环
  → MessageChatMemoryAdvisor：保存本轮 User / 最终 Assistant 消息
  → AgentResponse(conversationId, executionId, toolCallCount, content)
```

Memory Advisor 只在 `/api/agent` 单次调用上启用。共享 `ChatClient` 上的 `/api/chat`、`/api/chat/stream`、`/api/intent` 保持无状态。Spring AI 2.0.1 的 Advisor 默认顺序把 MessageChatMemoryAdvisor 放在 ToolCallingAdvisor 外层，因此内层可完成多步工具调用，外层保存最终回复。`ToolContext` 只传 Java 工具运行所需的 `AgentExecutionTrace`，不会传聊天消息；RunningTools 不依赖 ChatMemory。

## 四种不同的东西

| 概念 | 当前用途 |
| --- | --- |
| Context Window | 当前一次 Model Request 真正发送的 Token，包括 System Prompt、取回的 Memory、当前 User 消息、本次 Tool Result 等 |
| Chat Memory | 模型外的应用数据；选择有边界的对话上下文，下一轮重新加入 Context |
| Chat History | 长期、完整的消息归档；v0.4 不建设表或审计存储 |
| RAG | 对外部文档或知识源按查询检索，再注入相关内容；不等于对话 Memory |

这里的 short-term memory 是当前 conversation 的近期 User / Assistant 消息。一次 Agent 执行期间的 Tool Result、executionId、Tool 调用计数属于 working state / trace，不是 Chat Memory。长期用户目标、训练偏好或比赛计划属于可能的 long-term memory，需另行设计写入、检索、敏感信息与遗忘策略；v0.4 不自动抽取画像，也不把聊天历史做 Embedding。

## Spring AI 2.0.1 组件与窗口

- `ChatMemory`：按 conversationId `add`、`get`、`clear` 的抽象。
- `ChatMemoryRepository`：持久层抽象；当前 Bean 是 `InMemoryChatMemoryRepository`。
- `MessageWindowChatMemory`：在 repository 上实现有界消息窗口，默认配置 20 条，可用 `AGENT_MEMORY_MAX_MESSAGES` 设为 1–200。
- `MessageChatMemoryAdvisor`：在调用前取回历史并注入消息，在成功得到响应后写入当前 User / Assistant 消息。使用 `ChatMemory.CONVERSATION_ID` 传入命名空间。

窗口按 Spring AI 2.0.1 实际源码行为裁剪：超过上限时先按消息数计算切点，再向前推进到下一个 User 消息，使保留内容从完整回合开始。旧回合可能一次删掉多条，实际数量可能小于 `maxMessages`；System 消息会被特殊保留。RunAgent 的 System Prompt 每次单独传给 ChatClient，普通对话 Memory 主要包含 User / Assistant 消息。`maxMessages` 限制条数，不保证固定 Token 上限；可在重启应用后分别设为 4 与 20 观察 `memoryMessagesBefore/After` 和可用的 Usage 日志。

`clear(conversationId)` 可在服务内部使用，v0.4 不提供公开删除接口，因为当前没有身份认证和授权。`conversationId` 允许客户端指定，最长 100 字符且仅允许字母、数字、`-`、`_`；缺省时由服务器生成 UUID。它只用于 Memory 隔离，**不是认证，也不是授权**。未来多用户系统必须先确认 authenticated user，再校验该用户有权访问 conversationId；不应只凭一个 ID 读取或删除 Memory。当前单用户项目不引入 userId、Session Framework 或 Account 模型。

## Running Data、Tool Result 与 Trace

Memory 可以帮助模型理解“那段时间”指上一轮讨论的最近 30 天，但历史自然语言中的“跑了 120 km”不是当前权威数据。涉及个人跑量、次数、时长、配速或 PB 时仍应调用 `getRunningSummary`、`getRecentRuns` 或 `getPersonalBest`；比较时需取齐所需区间的数据。数据不可用时不得编造。

Spring AI 2.0.1 的框架自动 Tool Calling 会在本次 ToolCallingAdvisor 循环中使用 Tool Call / Tool Result；这些**中间消息不会自动以完整 transcript 的形式持久化进 Chat Memory**。当前 Memory 保存每轮 User 与最终 Assistant 对话上下文，不额外实现用户控制 Tool 执行、完整 Agent transcript 或 Tool Result 存储。`AgentExecutionTrace` 的 executionId、step、耗时和 Tool 计数属于观测元数据，也不进入 Memory。

日志只记录 conversationId、executionId、消息数量、Tool 名称/状态/耗时；`ChatResponse` 包含 Usage 时记录 promptTokens、completionTokens、totalTokens，缺失时不阻碍请求。最终响应中的 Usage 未必涵盖整个多步 Tool Loop 的所有内部调用，因此不能据此计算精确总成本。不记录用户消息、Assistant 全文、完整 Memory、密钥或原始轨迹。

## 生命周期与局限

`InMemoryChatMemoryRepository` 存在于单个应用进程。应用重启会丢失 Memory；Pod A 和 Pod B 各自持有独立内容，负载均衡到不同实例可能看不到上一轮。v0.4 有意先学习 Spring AI 抽象、conversationId、检索和注入，不接 Redis、JDBC 或 PostgreSQL。当前没有认证；同一 conversationId 的并发请求也不保证严格顺序。请求失败或超时后，Advisor 可能已写入 User 消息，阻塞 Provider 调用也未必被立即中断；不要把此实现视为事务性聊天记录。

v0.5 才研究 RAG 的 Document、Chunk、Embedding、Vector Store、Retriever、Hybrid Search 和 Context Injection。Memory 处理当前对话的连续性；RAG 检索外部知识。v0.4 不使用 VectorStoreChatMemoryAdvisor，也不引入 Vector DB、MCP、Workflow 或 Multi-Agent。
