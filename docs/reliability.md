# RunAgent v0.11 Reliability / Failure Governance

## 1. Agent Reliability Engineering 与学习目标

可靠性是在明确边界内识别、限制、隔离、降级和恢复故障。一次成功回答不足以证明可靠性；失败时也要守住个人事实、证据、预算和隐私。本阶段保持 Java 21 / Spring Boot 4.1.1 / Spring AI 2.0.1 / LangGraph4j 1.8.27。

## 2. Why Reliability 与范围

LLM、Running MCP、Weather MCP 会超时、限流或暂时不可用。治理主要覆盖 Multi-Agent 的模型与 MCP 调用，以及 Knowledge retrieval 的失败分类；普通 Chat、Streaming、Intent、Agent 和原 Workflow 模型语义保持原状。Workflow 另有独立 checkpoint 开关。没有全局 ChatModel Bean 替换。

```text
Multi-Agent request
  -> execution deadline -> execution budget -> reliability policy
  -> Retry -> shared dependency CircuitBreaker -> semaphore Bulkhead
  -> BudgetedChatModel / accepted MCP callback
  -> evidence -> Java fallback policy
  -> enough evidence: synthesis; no evidence: fail safe
```

## 3. Failure Taxonomy

Dependency 固定为 `LLM_GATEWAY`、`RUNNING_MCP`、`WEATHER_MCP`、`RAG`。FailureCategory：

| Category | 含义 | Retry |
| --- | --- | --- |
| TRANSIENT | 明确的502/503/504、网络IO等暂态错误 | retry-safe操作可重试 |
| PERMANENT | 400/401/403等客户端问题、非法参数 | 否 |
| RATE_LIMITED | typed HTTP429 | retry-safe操作可重试 |
| TIMEOUT | 供应商/依赖 attempt timeout，或任务deadline | 仅依赖timeout；任务deadline不能重试 |
| CIRCUIT_OPEN | 依赖熔断拒绝 | 否 |
| BULKHEAD_REJECTED | 并发许可不足 | 否 |
| BUDGET_EXCEEDED | 任务调用/Token/输出预算阻止 | 否 |
| CANCELLED | 取消/interrupt | 否 |
| INVALID_OUTPUT | 结构化输出错误 | 否 |
| DEPENDENCY_UNAVAILABLE | 领域依赖不可用，但无法证明是暂态 | 否；可计入Circuit |
| UNKNOWN | 无法可靠分类 | 否 |

## 4. Transient vs Permanent Failure

`FailureClassifier` 检查真实类型、status code及有循环防护的cause chain。实际核对OpenAI SDK 4.49.0的 `OpenAIServiceException.statusCode()`；支持Spring WebClient/RestClient的HTTP状态、Socket/HTTP Timeout、OpenAIIoException和IO causes。不读取exception message、body、headers文本或正则猜429。SDK/MCP如果丢失结构化故障信息，则UNKNOWN，不冒险重试。500当前也是UNKNOWN，未将所有5xx一概设为暂态。

## 5. Deadline

Multi-Agent沿用 `MultiAgentExecutionControl`，默认180s，是task-wide总deadline；SDK/MCP自身timeout是per-attempt限制。每次尝试、backoff前后、模型/工具前后检查同一个control。不能因handoff/retry创建新deadline。`remaining <= backoff + 10ms execution margin` 时直接TIMEOUT且不sleep。取消或interrupt后不能开始下一次操作。底层已经发出的请求仍是best effort取消。

## 6. Retry

默认关闭：`RELIABILITY_ENABLED=false`。开启后只有应用 `ReliabilityExecutor` 拥有retry决策。默认maxAttempts=2，包含初次调用和最多一次retry；可配置1–3。Retry在每次实际操作之外，重新进入Circuit/Bulkhead/BudgetedChatModel。

## 7. Exponential Backoff

初始200ms、multiplier=2、最大1s。attempt增加时基数200/400/800ms，最终随机间隔仍受maxDelay约束。maxAttempts=2时通常只发生一次间隔。程序化使用Resilience4j Retry AsyncContext决定次数/间隔，当前线程执行注入的Sleeper，没有异步后台worker或第二线程池。

## 8. Jitter

生产注入ThreadLocalRandom，默认±25%，即第一次通常150–250ms。测试注入固定随机源和不等待的Sleeper，精确断言随机边界与退避上限。避免所有失败请求同时再次冲击依赖。

## 9. Retry Storm

Agent本身有多个模型round与Tool Loop。多层retry会乘法放大流量、延迟和费用。限制总尝试数、task预算与deadline；不因可靠性无限扩展调用。

## 10. Single Retry Owner

`spring.ai.openai.max-retries: 0` 保持。没有Spring annotation/AOP，也没有额外TimeLimiter。合成本机HTTP网关验证：开启时429/503 -> 200恰好2个HTTP请求，400/401/403恰好1个；关闭治理时429恰好1个。重试由应用层显式发生。

## 11. Circuit Breaker

LLM/Running/Weather各一个固定实例：`llm-gateway`、`running-mcp`、`weather-mcp`；共享于请求之间，绝不能按executionId创建。RAG当前为本地检索，不套Circuit。

## 12. CLOSED / OPEN / HALF_OPEN

```text
CLOSED --failure threshold--> OPEN --wait--> HALF_OPEN
  ^                                         |     |
  +------------------- success -------------+     +-- failure --> OPEN
```

默认count window10、minimum calls5、failure rate50%、open30s、half-open2次探测。只记录TRANSIENT/RATE_LIMITED/依赖TIMEOUT/DEPENDENCY_UNAVAILABLE；非法输入/输出、budget/cancel/task deadline不污染Circuit。测试使用真实显式状态迁移API，避免等待30s。探测失败重新OPEN，成功CLOSED。

## 13. Bulkhead

采用Semaphore Bulkhead，LLM8、Running MCP4、Weather MCP4并发，等待时间0，许可不足立即BULKHEAD_REJECTED。阻塞Graph沿用boundedElastic，不再引入ThreadPoolBulkhead。

## 14. Circuit Breaker vs Bulkhead

Circuit针对持续故障；Bulkhead限制并发资源占用。Weather失败不会耗尽Running的许可。当前bulkhead是每JVM共享，不是集群全局限流。

## 15. Retry vs Circuit Breaker

Retry再次尝试短暂故障；Circuit拒绝持续失败依赖的新请求。组合顺序由显式代码和测试证明：Retry -> Circuit -> Bulkhead -> actual。Backoff期间已释放bulkhead permit，下一次重新检查Circuit/deadline/budget。拒绝时不进入BudgetedChatModel，不增加model-call。

## 16. Dependency Protection 与 Operation Safety

OperationSafety为READ_ONLY、IDEMPOTENT、NON_IDEMPOTENT。LLM生成没有业务写副作用，可有限retry，但可能重复计费，并非账单幂等。MCP只对已通过发现白名单的原始工具身份再次做retry allowlist：

- Running：getRunningSummary、getRecentRuns、getPersonalBest。
- Weather：openmeteo_search_locations、openmeteo_get_forecast、openmeteo_get_air_quality。

未知、null工具名和未来写工具默认NON_IDEMPOTENT。local Running不套MCP Circuit；MCP失败不回退本地工具。日期/地点/坐标约束先验证，再以同一参数执行有限retry。原始schema、metadata和structuredContent保留；MCP metadata仍为空，不发送budget/deadline/context。

## 17. Graceful Degradation

`FallbackPolicy` 用Java状态决定FAIL/DEGRADE/CONTINUE，不增加一次模型询问。成功Synthesis后程序仅对实际尝试且失败的来源附加固定证据限制，未选择的来源不是失败。限制描述本次缺少可用分析，不推断底层数据、检索或索引失效，避免只依赖模型自觉说明失败。没有工具证据的Specialist不能发布成功报告。

## 18. Failure Isolation

Specialist执行一次，失败也completed；内部依赖最多两次attempt不等于重新委派Specialist。一个模型请求的工具轮次仍计1，底层MCP retry另计attempts。持续故障通过Circuit/Bulkhead进一步隔离。

## 19. Critical vs Optional Dependency

| 状态 | 行为与事实边界 |
| --- | --- |
| Weather失败，其他有成功证据 | Synthesis继续，明确本次未纳入实时天气 |
| Knowledge失败，其他有成功证据 | 继续；未依据本次书籍检索证据 |
| Running失败，其他有成功证据 | 仅一般训练/天气参考；不声称依据个人跑量/PB |
| 全部Evidence失败 | MULTI_AGENT_INSUFFICIENT_EVIDENCE；不调用Synthesis，不退回模型常识 |
| Supervisor Route失败 | 有限依赖retry后整体失败；Java不猜路由 |
| Synthesis失败 | 整体失败；内部Reports不拼接为公共答案 |

任意自由文本LLM的事实忠实度仍需真实模型/人工评价；代码保证成功证据准入、失败报告为空、失败状态和固定限制传递，不能据此宣称形式化保证自然语言绝不幻觉。

## 20. Retry + Budget

`ReliableChatModel -> BudgetedChatModel -> raw model`，同一个ExecutionScope/ExecutionBudget贯穿整个图。每次实际attempt重新reserve、collect/reconcile，ToolCallingAdvisor内部模型round也受保护。preflight只检查不消费；Circuit/Bulkhead拒绝没有model-call。remainingCalls<=1阻止非Synthesis，最后一次保留给汇总。softToken超额停止可选工作，仍允许已有证据的Synthesis；不重置委派计数或模型输出cap。

## 21. Retry + Cost

429后成功：modelCalls2，失败attempt Usage UNKNOWN、成功attempt真实已知Usage，整单Cost UNKNOWN。超时可能已经计费，不能记作免费。gatewaySamples报告实际HTTP请求数、模型调用数、重试数、latencyMs、unknownCalls、costStatus。健康合成case使用固定Usage和测试价格，KNOWN不代表真实供应商价格。

## 22. Idempotency

```text
Request -> Write Tool -> server committed -> response lost
                                         -> Retry -> duplicate write
```

当前工具主要只读，重复查询不会重复创建业务实体；数据可能随时间变化，不能把只读等同结果完全相同。未来createTrainingPlan等写操作必须有server-side idempotency key、持久去重记录和结果复用，当前策略不能直接复用。

## 23. Checkpoint

独立 `RELIABILITY_CHECKPOINT_ENABLED=false`，不依赖全局可靠性开关。开启后TrainingAnalysisWorkflow构造时compile一次，使用官方 `MemorySaver`、`CompileConfig.builder().checkpointSaver(...)`，`RunnableConfig.threadId=workflow executionId`。

官方1.8.27有FileSystemSaver，但恢复分析需要问题和原始证据，而它们不应进入checkpoint。当前使用安全投影+有界临时运行上下文，选择MemorySaver，明确JVM重启后无法恢复。没有自建JDBC Saver或Java Object序列化。

已查[官方v1.8.28 release](https://github.com/langgraph4j/langgraph4j/releases/tag/v1.8.28)：修复updateState/asNode重复归约（PR#518）。本实现直接resume而不使用updateState，测试验证1.8.27可用，无需patch，更不升级1.9。

## 24. Resume

Java入口，无新增公共HTTP契约：

```java
String executionId = recovery.create(request);
try { recovery.run(executionId); } catch (WorkflowException failure) { /* retain executionId */ }
TrainingAnalysisResponse response = recovery.resume(executionId);
recovery.forget(executionId);
```

恢复使用真实 `graph.invoke(null, config)`，失败位置由官方checkpoint确定；不是手写expected path重新执行。测试路径：validateInput -> fetchRunningData SUCCESS -> retrieveKnowledge FAIL；resume：retrieveKnowledge -> analyze -> validateOutput -> END；Running总调用次数1，最终executedNodes五个且无重复。完成后resume直接返回缓存结果，不重复运行。失败节点可能再次执行，因此仍需要业务幂等。

显式resume是新的调用，创建新的Workflow deadline供剩余节点共享；同一调用内retry不重置deadline。没有无限自动resume，也没有后台job。

## 25. Checkpoint vs Memory

Checkpoint记住一次workflow进度；Conversation Memory记住对话消息；Multi-Agent共享State只属于一次请求。threadId不使用conversationId。Checkpoint allowlist仅executionId、mode、startDate、endDate、RunningSummary、executedNodes、analysisStatus；不包含question、Prompt、raw RAG Chunk、raw Running JSON、GPS、API Key、ToolContext、answer或exception。

问题/检索正文/答案只保留于最多64个JVM临时session，默认30min非活动过期，下一次访问惰性清理，可显式forget。活跃session不能forget或同时resume，容量耗尽fail fast；完成session也占容量直至forget/过期。没有持久化这些临时数据。

## 26. Checkpoint vs Idempotency

Checkpoint保存进度；Idempotency使相同业务操作重复执行不产生重复副作用。写入成功后、checkpoint提交前崩溃，resume仍可能重复调用写工具。需要执行ID+节点+业务操作的idempotencyKey及durable store；本阶段不实现写工具或durable idempotency store。

## 27. Running MCP Failure

只读503可有限retry；未知错误不retry；真实3项callback来自同一McpCatalog startup snapshot。无重新发现、无手写JSON-RPC、无Host local fallback。失败后个人事实不能由模型知识补齐。

## 28. Weather MCP Failure

StructuredWeatherToolCallback在启用路径于丢弃SDK异常前提取固定FailureCategory；安全异常无cause/stack/body。保留structuredContent和旧content兼容。Timeout最多两次后该Specialist不可用，其他成功证据可以继续。SDK若未保留typed status/cause则UNKNOWN，不猜503。

## 29. LLM Failure

有限retry的是单次模型round，不重新运行全部Agent/Tool Loop。Route/Synthesis是critical，没有模型切换或跨provider failover。非法Structured Output与截断输出不重试。预算消耗/Usage记录保持v0.10真实含义。

## 30. RAG Failure

本地索引不可用、配置错误通常不会被retry修复；不套Circuit或Bulkhead，默认不retry。零召回是有效no-context结果，不是TRANSIENT。Knowledge不可用时明确缺少本次书籍证据，不能装作引用私有四书。

## 31. Fault Injection

所有注入仅在src/test；无生产chaos API。核心测试注入typed status/timeout、固定随机源/Sleeper，Circuit用显式状态迁移，Bulkhead用Latch使两许可占用后第三调用被拒绝，没有脆弱sleep竞争。恢复用自创合成Running/RAG/Analysis。

```bash
./gradlew reliabilityVerification
```

独立JavaExec，test runtime classpath，不依赖test/check/build，不读取application-local.yml/.env、真实activities、书籍、索引、模型或公共MCP。包含核心故障、真实本地HTTP Gateway、真实Graph降级、相同七case开启/关闭对照，以及真正MemorySaver恢复。失败写安全报告后gate失败。

## 32. Reliability Observability

| Meter | 语义/固定tags |
| --- | --- |
| runagent.reliability.attempts | 每个actual attempt完成后计一次；dependency/outcome/failureCategory；成功category=NONE |
| runagent.reliability.retry | 已批准并准备backoff的retry；dependency/failureCategory；取消可能使后续attempt未发生 |
| runagent.reliability.degraded | 部分证据Synthesis成功；outcome/fallbackAction |
| runagent.reliability.circuit.rejected | 固定依赖被Circuit拒绝 |
| runagent.reliability.bulkhead.rejected | 固定依赖无许可 |
| runagent.reliability.recovery | phase START/RESUME、outcome SUCCESS/FAILURE |

复用resilience4j.circuitbreaker.*、resilience4j.bulkhead.*、resilience4j.retry.*原生metrics，不重复建Circuit state Gauge。只绑定这三种registry；不用TimeLimiter/RateLimiter。低基数Counter不包含executionId；关联执行沿用现有高基数Graph/Stage spans及model Usage。未另建含exception event的可靠性span。

## 33. Security / Privacy

安全异常只携带枚举，无cause/stack/body；不存完整供应商异常。可靠性metrics/reports没有Prompt、Answer、RAG正文、报告内容、Tool参数/结果、地点、用户问题、URL、Secrets。保留AiObservationErrorSanitizer、PrivacySafeVectorStoreConvention与全部敏感采集false。checkpoint路径`.run-agent/`已整体Git ignored。

JSON/Markdown报告位于build/reports/run-agent-reliability，只有固定caseID、期望/实际动作、计量单位/次数、结果/reasonCode和安全HTTP/质量样本统计。privacySafety为报告哨兵检查及相关单测的合成证据，不能当全面生产安全审计。

## 34. Current Limitations

没有multi-provider failover、失败自动换模型、persistent multi-agent checkpoint、durable idempotency store、write-side tools、parallel specialists、hedged/speculative requests、production chaos platform、distributed bulkhead、service mesh。取消仍best effort。Saver及临时上下文仅当前JVM；没有认证/public resume入口。策略参数均为学习初始值，不是生产最佳值。合成契约通过不证明真实模型质量或稳定性能提升，未运行真实供应商故障注入。

## 35. Production Evolution

先用相同case评估质量、尾延迟、实际attempt/model calls、Usage/Cost完整度，再调整策略。未来durable recovery须先定义安全序列化/证据再获取/访问控制/保留策略，再接官方持久Saver；写工具先设计durable idempotency。多实例保护需要单独评审，不能把单JVM Semaphore称分布式治理。

## 配置

```yaml
run-agent:
  reliability:
    enabled: ${RELIABILITY_ENABLED:false}
    retry:
      max-attempts: ${RELIABILITY_RETRY_MAX_ATTEMPTS:2}
      initial-delay: ${RELIABILITY_RETRY_INITIAL_DELAY:200ms}
      multiplier: ${RELIABILITY_RETRY_MULTIPLIER:2.0}
      max-delay: ${RELIABILITY_RETRY_MAX_DELAY:1s}
      jitter: ${RELIABILITY_RETRY_JITTER:0.25}
    circuit-breaker:
      sliding-window-size: 10
      minimum-number-of-calls: 5
      failure-rate-threshold: 50
      wait-duration-in-open-state: 30s
      permitted-calls-in-half-open-state: 2
    bulkhead:
      llm:
        max-concurrent-calls: ${RELIABILITY_LLM_MAX_CONCURRENT:8}
      running-mcp:
        max-concurrent-calls: ${RELIABILITY_RUNNING_MAX_CONCURRENT:4}
      weather-mcp:
        max-concurrent-calls: ${RELIABILITY_WEATHER_MAX_CONCURRENT:4}
    checkpoint:
      enabled: ${RELIABILITY_CHECKPOINT_ENABLED:false}
```

启动校验：attempts1–3；delay>0、max>=initial；multiplier>=1；jitter0–1；window>=minimum>=1；failure rate1–100；open duration>0；halfOpen>=1；concurrency>=1。NaN/Infinity数值也拒绝。

| Pattern | 解决什么 |
| --- | --- |
| Timeout / Deadline | 最多等多久 |
| Retry | 短暂失败再尝试 |
| Circuit Breaker | 持续故障时快速失败 |
| Bulkhead | 限制故障/负载影响范围 |
| Fallback | 主能力失败后的证据降级 |
| Checkpoint | 记住执行进度 |
| Idempotency | 避免重复副作用 |
