# RunAgent v0.8 Observability

Logs 描述具体事件，例如某次 Tool/Node 失败；Metrics 描述整体趋势，例如请求数、成功率、耗时分布和 Token usage；Traces 展示单次请求在 HTTP、LLM、Tool、Retrieval、Workflow Node 之间的耗时关系。三者互补，不能用“日志有 executionId”代替分布式 Trace。

Evaluation 是有 Ground Truth 的离线质量判断；Observability 是运行时行为监测。线上没有相关文档标签，RAG match count 不是 Recall@K。Judge 不进入线上请求，也不注册 Recall/Judge 分数为在线 Gauge。

## 自动配置与 Profile

保持 Boot 4.1.1 / Spring AI 2.0.1。Actuator、micrometer-registry-prometheus、spring-boot-starter-opentelemetry 由 Boot 依赖管理，VectorStore observation 自动配置模块由 Spring AI 2.0.1 BOM 管理。没有手工建立 OpenTelemetrySdk、TracerProvider 或 OTLP exporter。

默认启动：Management 单独绑定 `127.0.0.1:9091`，仅暴露 health，详情关闭。Micrometer 本地统计仍可用，网络 Trace、OTLP Metrics、OTLP Logs export 全部关闭。OpenTelemetry starter 会默认打开 OTLP Metrics，因此必须显式关闭，避免普通启动和测试尝试向 collector 外发。

```bash
./gradlew bootRun --args='--spring.profiles.active=local,observability --server.address=127.0.0.1'
# MCP 模式：
./gradlew bootRun --args='--spring.profiles.active=local,mcp,observability --server.address=127.0.0.1'
```

observability profile 仅开放 health/info/metrics/prometheus，启用 Trace 与本地 100% sampling。没有开放 env、configprops、beans、heapdump、threaddump、loggers、shutdown。项目无认证，不能把 Management 绑定 0.0.0.0，也不能通过无鉴权反代公开。

当前 Boot 4.1.1 实际配置 metadata 已核实：

| 属性 | 用途 |
| --- | --- |
| management.tracing.export.enabled | 默认 false；观测 profile true |
| management.opentelemetry.tracing.export.otlp.transport | http |
| management.opentelemetry.tracing.export.otlp.endpoint | OTLP HTTP 完整 traces URL |
| management.otlp.metrics.export.enabled | false；指标用 Prometheus pull |
| management.logging.export.enabled | false |
| spring.reactor.context-propagation | auto，跨 Reactor 线程传播 observation context |

`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` 默认 `http://127.0.0.1:4318/v1/traces`。此变量是完整 HTTP traces URL，不能填仅端口根地址；management 端口可用 `MANAGEMENT_PORT` 覆盖。100% sampling 只用于本地学习，通常不适合高流量生产。

## 隐私边界

实际 2.0.1 属性显式关闭：

```properties
spring.ai.chat.client.observations.log-prompt=false
spring.ai.chat.client.observations.log-completion=false
spring.ai.chat.observations.log-prompt=false
spring.ai.chat.observations.log-completion=false
spring.ai.chat.observations.include-error-logging=false
spring.ai.tools.observations.include-content=false
spring.ai.vectorstore.observations.log-query-response=false
```

日志 flag 不覆盖所有 Trace 属性。本阶段通过合成本机网关和内存 OTel exporter 实测并补上两个缺口：

- 默认 VectorStore convention 即使不记录 query-response 日志，仍导出 `db.vector.query.content`。KnowledgeIndexService 使用 PrivacySafeVectorStoreConvention，去掉 VectorStore 高基数属性，保留操作级低基数维度与耗时。不能把查询正文、filter 或返回文档送入 span。
- Provider 异常会携带完整响应内容，tracing handler 会写 exception event。AiObservationErrorSanitizer 用 Boot 的公开 ObservationHandlerGroup 扩展点先于 tracing group 执行，替换为无 cause、无堆栈的固定 AI error。仅给普通 handler 标 @Order 不足以先于 Boot 的 grouped tracing handler。按 Spring AI Context 类型选择 handler，因为 convention-backed observation 在选 handler 时还没有 name。

清洗只改变观测副本，原始领域失败仍按现有业务错误路径返回。代价是 exporter 不再有供应商原始异常类型/堆栈；定位依靠安全 outcome、HTTP status、节点名和现有 Tool 日志。保留 OpenAI/MCP/Graph/Tika 等既有 payload 日志禁用策略。不要在真实数据环境开启 DEBUG 试图获取 Prompt 或底层错误正文。

executionId/workflowExecutionId 可作 Trace high-cardinality attribute 与安全日志字段；不得作 Meter tag。conversationId、queryId、问题、Prompt、地点、书籍标题+页码组合、工具参数都不能作低基数维度。Metrics 的动态 UUID 会导致每次请求新增时间序列，即 Cardinality Explosion。

## 内置与业务观测

Built-in first，business metrics second。Spring AI 自己观察 ChatClient、Advisors、ChatModel、Tool、EmbeddingModel 与 VectorStore；不再手写同名 Timer，也不重复累计 Token。

| Base name | 作用 |
| --- | --- |
| spring.ai.chat.client | ChatClient 调用 |
| gen_ai.client.operation | ChatModel 调用 |
| gen_ai.client.token.usage | Provider 实际返回的 Token usage |
| spring.ai.tool | Tool 调用 |
| db.vector.client.operation | VectorStore 操作 |
| http.server.requests | Boot HTTP 请求 |

这些是实际测试确认的 base Meter/Observation 名，Prometheus 会转换为下划线与单位后缀。不要根据别的 Spring AI 版本硬编码完整 Prometheus 指标名。Provider 没返回 usage 时不补零；Agent 最终手工 usage 日志也不能自动当作整个 Tool Loop 的精确总成本。

| 自定义名称 | 定义 | 低基数属性 |
| --- | --- | --- |
| runagent.agent | 一次 Agent 业务执行的 Observation/Timer | outcome=success/error/limit_exceeded |
| runagent.agent.tool.calls | DistributionSummary；每次请求 finally 记录一次最终 Tool 数 | 无动态 tag |
| runagent.workflow | 一次 Workflow 执行 | mode、outcome |
| runagent.workflow.node | 每个实际开始执行的节点 | node、outcome |
| runagent.rag.matches | 每次完成 Retrieval 的命中数，零召回记录 0 | 无动态 tag |
| runagent.rag.requests | Retrieval 次数 Counter | outcome=success/no_context/unavailable/error |

Workflow mode 只有三个已知值；校验前无效 mode 使用固定 invalid 分类。node 只允许五个固定节点。错误业务 Observation 只导出固定错误；Workflow deadline 记录 timeout。Agent 的 Reactor Controller timeout 可能早于阻塞业务完成，HTTP metrics 描述客户端请求状态，业务 timer 描述内部实际执行；不能把二者混为同一成功率。

AnalysisNode 仍独立创建 ChatClient，但显式注入 ObservationRegistry 并使用默认 observation conventions，不继承 Agent/QA 的默认 Tools/Memory/Advisor。per-call 关闭 ToolCallingAdvisor 自动注册，拒绝模型意外 Tool Call。测试实际断言 ChatClient Observation 出现、消息只有两条、无 Tool/Memory 数据。

KnowledgeIndexService 给手工 SimpleVectorStore 注入同一个 ObservationRegistry。其 fingerprint、save/load、manifest 与 checksum 逻辑不变；仅新增观测不要求已有四书索引 Reindex。TracingToolCallback 保留原有安全日志，不重复包 Tool span。Workflow deterministic MCP 调用由 fetchRunningData Node 总体耗时覆盖，不额外套 Agent Tool timer。

## 本地 Jaeger + Prometheus

2026-11-01 通过官方 Releases 核实：

- [Jaeger v2.22.0](https://github.com/jaegertracing/jaeger/releases/tag/v2.22.0)，发布于 2026-10-06；镜像 `jaegertracing/jaeger:2.22.0`。
- [Prometheus v3.15.1](https://github.com/prometheus/prometheus/releases/tag/v3.15.1)，发布于 2026-10-21；镜像 `prom/prometheus:v3.15.1`。

```bash
docker compose -f compose.observability.yml config
docker compose -f compose.observability.yml up -d
# 本次实验结束：
docker compose -f compose.observability.yml down
```

此 compose 使用 Docker 桥接网络和显式端口发布，适用于 Docker Desktop。宿主机监听 `127.0.0.1:16686`（Jaeger UI）、`127.0.0.1:4318`（OTLP HTTP）和 `127.0.0.1:9090`（Prometheus UI）。容器内的 HTTP 服务监听 `0.0.0.0`，Compose 将访问入口限定为宿主机回环地址。Jaeger 使用有限内存存储，Prometheus 保留1天数据，均为学习环境。

Prometheus 通过 `host.docker.internal:9091/actuator/prometheus` 访问宿主机 RunAgent。2026-11-07 已在 Docker Desktop 4.24.2 上实测，容器可以访问当前绑定回环的 Management 服务。Linux Docker 的宿主机解析和回环可达性需要按环境配置，也可以在宿主机运行 Prometheus，将抓取目标设为 `127.0.0.1:9091`。

`observability/jaeger.yml` 使用容器内部监听地址。原生运行 Jaeger 时，应将其 HTTP 监听配置为宿主机回环地址。

启动成功后：

- [Actuator health](http://127.0.0.1:9091/actuator/health)
- [Prometheus scrape](http://127.0.0.1:9091/actuator/prometheus)
- [Prometheus UI](http://127.0.0.1:9090)
- [Jaeger UI](http://127.0.0.1:16686)

2026-11-07 已实际启动两个容器并确认 Jaeger UI、Prometheus UI 和 OTLP HTTP 接口返回200，Prometheus 当前目标为 up。Docker Desktop 4.24.2 的原 host networking 配置将服务留在 Linux VM 内部，改为桥接端口发布后，宿主机页面访问恢复。用户随后升级到 Docker Desktop 4.98.0，并确认两个页面正常使用。

应用需启用 `observability` profile 并设置 `management.tracing.export.enabled=true` 才会导出 Trace。IDEA 中若先前添加了 `--management.tracing.export.enabled=false`，将它改为 `--management.tracing.export.enabled=true`，重启应用并发起一次请求，然后在 Jaeger 选择 service `run-agent`。

## 实际 Trace 证据与限制

合成本机 HTTP 网关 + Boot 自动配置 + 内存 exporter 测试实际捕获的 Agent 关系（名称以 exporter contextual name 为准）：

```text
http post /api/agent
  runagent.agent
    spring_ai chat_client
      message_chat_memory
        tool _calling
          call → chat synthetic-gateway → POST
          execute_tool getRunningSummary
          call → chat synthetic-gateway → POST
```

工具 span 是 ToolCallingAdvisor 的子级，与 model-call advisor 同级；不能画成“model span 总是包住 tool span”。业务 Scope 与 Reactor auto context propagation 在此同步 Tool Loop 的合成路径建立了关系，但不保证任意供应商、异步跳转或 Streaming 都形成完整无断裂树。

COMBINED 的合成 exporter 测试验证 runagent.workflow、五个 runagent.workflow.node、真实向量 query 和独立 Analysis ChatClient/ChatModel，且导出属性与错误事件不含敏感哨兵。实际期望路径为 validateInput → fetchRunningData → retrieveKnowledge → analyze → validateOutput；前一步失败，后续节点不启动。没有通过构造假的 span 来补齐图形。

`/api/chat/stream` 保留原有 Flux、取消传播和 UTF-8 SSE；当前没有用真实 streaming provider + Jaeger 复验 parent-child。异步 HTTP/model span 可能断开，本阶段不重写 Streaming。Workflow deadline 仍是 best-effort cancellation，不能保证终止已发出的底层网络请求。

## P95 与 Histogram

P95 表示95%的请求耗时不超过该值；maximum 不是 P95。observability profile 为三个自定义 timer 配置 percentile histogram。真实 Prometheus endpoint 测试已确认 `runagent_agent_seconds_bucket` 存在，可使用：

```promql
histogram_quantile(0.95,
  sum by (le) (rate(runagent_agent_seconds_bucket[5m]))
)
```

Workflow/Node 可按 mode/node 聚合 bucket。没有 histogram bucket 时，不能用 histogram_quantile 声称得到可信 P95；样本过少或时间窗太短也不能支持性能结论。不要在 Java 每次排序计算全局 P95。

更多验证结果见 [v0.8 验证](v0.8-verification.md)。

## Multi-Agent Observation

v0.9 新增三个名称。

| 名称 | 定义 | 低基数属性 |
| --- | --- | --- |
| runagent.multi_agent | 一次 Multi-Agent execution 的 Observation/Timer | outcome |
| runagent.multi_agent.specialist | 一次实际开始的角色阶段 | specialist、outcome |
| runagent.multi_agent.delegations | DistributionSummary，每请求最终记录一次 delegationCount | 无动态 tag |

specialist 只有 running、knowledge、weather、supervisor_route、supervisor_synthesis。outcome 为 success、unavailable、timeout、error。multiAgentExecutionId 只作 high-cardinality span 属性或安全日志，问题、task、location、report、tool arguments 均不作 tag 或 span 内容。

一次请求包含多个 LLM calls、多个专业阶段和若干 Tool Loops。仅 HTTP latency 无法判断哪个角色消耗了时间，或模型产生了多少实际 Token。复用 Spring AI ChatClient/ChatModel/Tool/VectorStore spans 与供应商实际返回的 usage，不另外估算 Cost。

真正达到循环上限或已无可选能力时，Graph 仍经过 supervisor 节点，但不创建新的 Router LLM 阶段 Observation。尚未开始的 Specialist 没有 span。失败阶段保留固定 outcome，单项失败后的整个请求可以是 success。取消后正在执行的 Provider 可能晚返回，业务 Observation 会在其实际结束时停止，HTTP 请求可以先超时。

保留 AI error sanitizer、VectorStore privacy convention 和敏感内容采集 false。额外关闭 `org.springframework.ai.model.tool.DefaultToolCallingManager`，因为 Spring AI 2.0.1 对未知工具名的 WARN 会直接记录模型提供的字符串，可能包含复制的私有内容。日志捕获回归测试覆盖该情况。

Boot + 合成本机 HTTP 模型网关 + 内存 OTel exporter 已实际验证循环 Graph 的 HTTP、业务、模型、Tool 和向量检索处在同一 Trace，安全哨兵没有进入 attributes/events。当前机器没有运行 Docker daemon，v0.9 未执行 Jaeger/Prometheus 端到端复验。实际树与执行边界见 [v0.9 验证记录](v0.9-verification.md)。

Synthesis 普通日志新增 phase=SYNTHESIZE 的 STARTED 与 SUCCESS/FAILED、latencyMs，关联 multiAgentExecutionId。仅记录阶段元数据，汇总文本与供应商异常保持在日志之外。

2026-11-08实验E暴露了SDK重试与取消收尾问题。Knowledge模型阶段出现两次约60秒的HTTP等待，整次请求在180秒deadline结束。SDK默认重试现显式关闭；Root和阶段的异常路径优先检查执行控制，使取消后的失败归类timeout。HTTP已经取消时，迟到异常在本次Callable边界结束，避免重复onErrorDropped日志。回归测试捕获真实丢弃信号，并用本地HTTP500服务验证SDK只请求一次。


## v0.10 Performance Stage / Model Tier / Budget

Built-in `gen_ai.*` model telemetry (以及当前 Spring AI 导出的模型、Tool、vector meters) 继续负责底层耗时与用量；RunAgent 的 `runagent.performance.*` 负责 task/stage governance，避免复制一套 Token Histogram。

- `runagent.performance.model` Observation：每个真实ChatModel round，低基数 `performance.mode`、`model.stage`、`model.tier`、`outcome`、`usage.status`；模型名与executionId仅high-cardinality。
- `runagent.performance.model.calls`：每次完成的模型尝试计一次，包括失败与Tool Loop内部调用；tags为stage/tier/status，在任务finally按每条record刷新。
- `runagent.performance.usage.calls`：stage/usageStatus计数，帮助识别Usage完整度。
- `runagent.performance.budget.exhausted`：每任务每reason最多一次，固定deadline/model_calls/soft_tokens/output_tokens；soft_tokens只是软停止，output_tokens标记供应商超限/截断。
- 原有`runagent.multi_agent.specialist`继续度量整个Stage；Snapshot中的modelDuration是其中模型子集，不能把两者相加当总延迟。

UNKNOWN不输出0；Snapshot保留已知subtotal并将tokenEnforcement标为DEGRADED，完整Token和成本只在全部完整时报告。Cost主要位于Snapshot/Benchmark，未新增货币Histogram。异常只记录安全状态，保留AiObservationErrorSanitizer、PrivacySafeVectorStoreConvention与全部敏感采集false。每请求日志无Prompt、Answer、Report、Tool参数/结果、书籍或API Key。

Observability告诉我们“发生了什么”，Governance规定“允许消耗多少、何时停止”。配置、关键路径与学习图见[Performance](performance.md)。

逐轮安全日志增加finishReason固定枚举、outputChars（Unicode code point计数）与nullable reasoningTokens。后者仅采集原始SDK已报告且不超过completion的子集，不读取推理文本、不重复计费；未返回明细显示UNKNOWN。任意Provider结束标记不原样写日志，统一映射安全枚举。这些数值用于区分正文长度、生成截断与可能的推理消费，不把字符数转换成伪精确Token，也不据缺失明细断言模型开启了推理模式。

启动/逐轮日志额外包含固定枚举reasoningEffort，模型Observation增加低基数model.reasoning_effort。它表示受信配置请求的策略，不是Provider实际消费证明；NONE下缺失reasoningTokens仍UNKNOWN。只有OPTIMIZED显式Stage配置覆盖推理选项，默认及BASELINE保持原值；实际生效与质量需人工真实复测，详见[阶段推理策略](performance.md#按阶段配置推理策略)。
