# RunAgent v0.8 Evaluation / Observability Design

状态：用户于 2026-11-01 确认并要求按设计直接实现。方案选择：用户于 2026-11-01 选择独立 Gradle JavaExec Runner。

Builder check：独立只读审查已完成，无需用户补充的关键问题。该审查发生在业务实现之前。

## 目标与现状

v0.8 建立“构建 → 评估 → 观察 → 定位 → 改进 → 回归评估”的学习闭环。Evaluation 回答系统做得对不对；Observability 回答执行中发生了什么。二者有独立的入口、数据与指标。

起点为 `main` 上已提交的 `8f34791`，工作树原先干净。实际执行 `./gradlew test build` 成功，但命中缓存；随后 `./gradlew test --rerun-tasks` 成功，XML 汇总为 128 tests、0 failures、0 errors、2 skipped。这是修改前基线。

已阅读项目指南、README、Agent Loop、Memory、RAG、MCP、Workflow 文档及 v0.5–v0.7 验证报告，并检查对应实现。当前：

- Agent 复用自动配置 ChatClient Builder，Memory 只在 agent 调用时添加；ToolContext 保留本地执行计数。
- Workflow compile 一次，三种显式 mode，五个节点；节点包装已经处理安全日志和前后 deadline/cancel 检查。
- AnalysisNode 直接从 ChatModel 建立 ChatClient，绕过自动配置的观测设置。
- KnowledgeIndexService 手工建立 SimpleVectorStore，未注入 ObservationRegistry；已有 fingerprint、checksum、manifest 与原子持久化流程。
- MCP 发现与白名单保持启动快照；动态工具的安全日志不保存参数和结果。

需求附件在第 107 节“如果可”处截断；本设计覆盖之前完整要求，并补充 management endpoint 暴露与绑定验证。未推断任何截断后的额外要求。

## 约束与非目标

升级应用版本为 `0.8.0-SNAPSHOT`；保持 Java 21、Gradle 9.7.1、Boot 4.1.1、Spring AI 2.0.1、LangGraph4j Core 1.8.27。只修改 run-agent，不改前端、Running MCP Server 或真实数据。

不改变现有 API、Agent Loop、Memory、RAG 来源、MCP 白名单、Workflow mode、工具上限和取消语义。不增加 Multi-Agent、Supervisor、持久化评估平台、评估 REST API、Prompt 优化、在线 Judge、重试 Graph 或数据库。v0.9 仍未实现。

普通 test/build 无真实模型调用、公共 MCP、8090 Server、Ollama 或真实书籍依赖。不得自动下载模型。未经显式 live 开关，不发出付费评估请求。

## 已考虑的方案

1. **独立 JavaExec Runner（已选择）**：复用测试依赖和合成 fixture，独立执行评估与生成报告。Runner 不依赖 JUnit 的测试发现与报告生命周期，便于区分质量报告和单元测试。
2. **JUnit Tag 套件**：利用 Test task 包含/排除 tag，配置稍少，但评估报告和 JUnit 生命周期耦合更多。

以下设计随用户确认进入实施。

## Evaluation 组件与生命周期

评估代码放在 `src/test/java/cn/watsonzhu/runagent/evaluation/`，使用 Java record 和少量具体类；不打入应用 JAR。普通 JUnit 测试验证评分器、报告、Runner 和观测契约；JavaExec 使用 test runtime classpath，但不会运行 JUnit 测试或启动完整生产应用。

`evaluation`：读取版本化 JSON → 构造合成依赖 → 直接调用 Java 组件 → 捕获实际结果 → 评分 → 聚合 → 写 JSON/Markdown → 检查离线阈值。即使阈值未达标，也先生成失败报告。禁止 HTTP 调自己的 API。

`liveEvaluation`：独立 JavaExec，默认输出 `Live evaluation disabled`；只有 `LIVE_EVALUATION_ENABLED=true` 才构建真实 ChatModel。缺少必要网关配置时明确 SKIPPED，不伪装执行成功。该任务不属于 build；默认 report only。每个 case 有有限工具调用与执行期限，失败按安全 reasonCode 记录，不打印供应商异常内容。

### 数据集和可重复性

`src/test/resources/evaluation/` 包含 tool-cases、rag-cases、workflow-cases、synthetic-knowledge 及合成 activities/model scripts。每个文件携带 datasetVersion；报告记录各文件 SHA-256 与 suite checksum。固定绝对日期，不使用相对日期。Question、预期值、脚本模型响应分开表达，不能把 expectedTools 直接作为实际调用结果。

工具用例约 10–12 个，覆盖三个 Running Tools、不同距离 PB、No Tool、两段 Summary、多步 Summary + Recent Runs。Weather 如加入，仅使用 fake schema/callback，固定日期和合成地点；不访问公共服务。

### 离线 Tool Evaluation 的证据边界

脚本 ChatModel 驱动真实 ChatClient / ToolCallingAdvisor 与 RunAgentService。独立 RecordingToolCallback 捕获实际名称、JSON 参数和顺序；Running callback 使用真实 RunningTools / RunningDataService 读取合成 fixture。额外检查合成数据计算结果，避免只确认调用外观。

这验证的是 **SCRIPTED_MODEL_CONTRACT**，包括框架执行、参数、循环和记录链路。它不能证明模型理解自然语言或真实选择工具的准确率。报告必须携带该证据类型；真正的模型选择准确率由相同用例的 **LIVE_MODEL** 执行提供。负例测试通过错工具、错参数、多余调用和逆序验证评分器确实拒绝回归。

Tool Selection 以实际调用匹配 allowedToolSets 中任一允许路径；保留重复调用次数，不能简单转 Set。默认顺序无关，ordered=true 时逐项匹配。Tool Argument Case Accuracy 只有完整匹配某条允许路径及全部参数才算正确；缺字段、多余字段、错类型或额外调用均失败。JSON 对象字段顺序无关；ISO 日期按 LocalDate、数值按语义比较，字符串数字不等同数值；No Tool case 必须确实零调用。

### RAG Retrieval Evaluation

合成语料至少覆盖轻松跑、阈值训练、长距离和无关内容。Fake Embedding 通过固定文本特征生成向量，不读取 relevantDocumentIds；真实 SimpleVectorStore 执行检索，并复用 KnowledgeSearchService 的 SearchRequest 路径。该结果只证明合成检索回归，不证明 bge-m3 或真实四书召回质量。

Recall@K = 去重命中的相关文档数 / 已标注相关文档数；Precision@K 使用固定 K 作分母，实际返回不足 K 也不改变分母；RR 为 Top-K 内第一个相关结果排名的倒数，MRR 为各 query 的 RR 平均。结果去重后保留首次出现的排名，避免重复 ID 抬高分数。无 relevant labels 的 case 不参与有标签的召回率平均，明确标为 unjudged；另用 expectedNoContext case 验证零召回，不将缺少标签解释成完美召回。K 必须大于零。

每个 case 可给出明确的最小召回与 RR；suite 阈值用于离线 gate。Precision@K 的阈值要尊重该 fixture 的标签数和固定 K，不强求只有一个相关文档的 Top5 达到 1.0。所有阈值范围验证为 [0,1]。

### Workflow Routing Evaluation

用真实 TrainingAnalysisWorkflow / StateGraph 和真实五节点，注入合成 Running gateway、合成 retrieval 与 stub analysis model。分别执行三种 mode，对比实际 executedNodes 与 expectedNodes，严格顺序匹配。route accuracy 目标为 1.0。失败和取消由普通回归测试验证只开始必要节点，不能把预期路径直接当实际路径。

### Live 与 Judge

真实 Target Model 使用显式配置的 AI_GATEWAY_*；工具和默认 RAG context 仍是合成数据。RecordingToolCallback 与生产 TracingToolCallback 完全独立；不通过解析日志获取参数。低 temperature 不保证确定性；只在明确配置时记录 temperature。

`EVAL_JUDGE_ENABLED=false`，兼容需求中的 `JUDGE_ENABLED` 作为回退；仅 liveEvaluation 允许启用。使用 2.0.1 RelevancyEvaluator 与 FactCheckingEvaluator，限定合成 question/context/answer；`EVAL_JUDGE_MODEL` 可独立指定模型，否则复用 Target Model，在报告明确 judgeModel==targetModel。Judge 分数与错误单独报告，不能成为线上的请求链路或 Micrometer 指标。

Judge 不是 Ground Truth，会有偏差、错误、Prompt 敏感性、自我偏好、成本与非确定性。FactChecking 仅评估给定 context 对 answer 的支持，不能证明跑步建议科学、安全或医学正确。人工评估只写方法说明，不建平台。

### 本地私有评估与 baseline

`.run-agent/` 已被忽略，明确文档化 `evaluation/rag-golden.json` 与 `evaluation/baseline.json`。不生成假的人工标签。Golden schema 支持人工维护 question、expected source title、可选章节/页码和稳定 document IDs；按实际标注方式分别报告文档或来源命中，不能把来源命中叫文档 Recall。

只有 liveEvaluation 显式启用且提供私有 golden 时才运行额外本机检索；复用现有索引与 Ollama，不重建、不自动下载，不向 Target/Judge 发送私有正文。报告标为 LOCAL_PRIVATE_DATASET，只写安全 case ID、计数、指标和 reasonCode。

可选 baseline 对比只在数据版本、checksum、执行类型及指标定义一致时显示 delta；不兼容时写 BASELINE_INCOMPATIBLE，不默默比较。无 updateBaseline 自动覆盖，不增加实验数据库。

### 报告

离线输出 `build/reports/run-agent-evaluation/report.json` 与 `report.md`；live 输出独立子目录，避免覆盖离线结果。记录时间、应用版本、执行类型、dataset versions/checksums、各类 case 数、聚合指标、passed/failed/unjudged/skipped，以及 caseId/category/pass/metrics/reasonCode。Live 增加 model/provider、可选 temperature、Judge 状态与评分。

报告 DTO 不包含 question、Prompt、回答、chunk、原始参数/工具结果、密钥或异常详情。自由文本 reason 禁止透传；case IDs 与配置身份字段需有长度/格式约束。测试用敏感哨兵检查 JSON 与 Markdown，测试报告写临时目录。

## Observability

### 依赖、配置与安全

新增 Boot Actuator、Micrometer Prometheus Registry、Boot 4.1.1 OpenTelemetry starter，全部由现有 BOM 管理。实施时读取 4.1.1 实际依赖、配置 metadata/source，确认 OTLP transport、endpoint 和 exporter enable property；不照搬 Boot 3 属性，不手建 OpenTelemetrySdk。

默认启动仍提供本地 Micrometer 统计，但不启用网络 Trace export。`observability` profile 打开本地 exporter 与 sampling=1.0；endpoint 经环境变量配置，YAML 可给回环默认值。Management 独立 9091、绑定 127.0.0.1；默认 health，观测 profile 仅 health/info/metrics/prometheus。health 不输出依赖细节。敏感 endpoint 不开放。

当前缓存中已核实 2.0.1 的 ChatClient log-prompt/log-completion、ChatModel log-prompt/log-completion/include-error-logging、tools include-content 属性默认均为 false；配置中显式写 false。VectorStore query/response 属性在实施时从实际 artifact metadata/source 核对后配置，避免无效属性造成安全假象。保留现有 OpenAI、MCP、Graph 等安全日志禁用策略。自定义 Observation 不携带原始异常 message/response。

### 最小业务观测

小型 `RunAgentObservation` helper 接收 ObservationRegistry 与 MeterRegistry：

| 名称 | 类型/边界 | 低基数属性 |
| --- | --- | --- |
| runagent.agent | 单次 agent 业务 Observation / Timer | outcome=success/error/limit_exceeded |
| runagent.agent.tool.calls | DistributionSummary，每请求 finally 记录一次最终调用数 | 可选 outcome |
| runagent.workflow | 单次 Workflow Observation / Timer | mode、outcome |
| runagent.workflow.node | 每个实际开始节点 Observation / Timer | 五个固定 node、outcome |
| runagent.rag.matches | DistributionSummary，每次完成检索的命中数，零召回记录 0 | 无 query/source 属性 |
| runagent.rag.requests | Counter | outcome=success/no_context/unavailable/error |

executionId/workflowExecutionId 只作 Trace high-cardinality attributes 与安全日志字段。conversationId、queryId、question、地点、书页、工具参数不得进 Meter tags。unknown/null mode 等验证失败使用固定分类，禁止原始字符串标签。

Agent 外层 Reactor timeout 不等于内部阻塞调用已经停止；保留现有 Controller timeout 和 HTTP metrics，不把后台完成冒充请求成功。Workflow 继续使用同一个取消标记和 deadline。业务 Observation finally 停止，失败只覆盖已开始节点。

### 内置观测接入

AnalysisNode 创建独立 ChatClient，使用实际 2.0.1 ChatClientBuilderConfigurer 与 ObservationRegistry 接入观测；不继承 Agent/QA 的默认 Tools/Memory/Advisor。若实际 configurer 会附带共享默认项，则直接使用显式 registry/convention 构建等价 client。保留 TOOL_CALLING_ADVISOR_AUTO_REGISTER=false，以及拒绝意外 Tool Call 的验证。

RagConfig 向 KnowledgeIndexService 注入 ObservationRegistry，SimpleVectorStore.builder(...).observationRegistry(...)。fingerprint 的输入完全保持不变，已有索引不因观测变更而 Reindex；save/load/checksum/manifest 的回归必须继续通过。

Spring AI 负责 ChatClient、ChatModel、Tool、Embedding、VectorStore 的时间与 Token Usage，不再套同名 Timer。TracingToolCallback 只保留现有安全日志；deterministic MCP 由 fetchRunningData Node 覆盖总体耗时，本阶段不增加额外 MCP timer。没有 provider usage 时不补 0。

业务 span 在执行线程打开/关闭 scope；Reactor 与 Graph 异步边界按实际框架能力传递 observation context，不把 Observation 放进可序列化 State 或 MCP metadata。实测记录 parent-child 结果；不会为美化树伪造 span，也不重写 Streaming 来规避上游限制。

### 本地学习 Stack

新增仅 Jaeger + Prometheus 的 compose 文件和 Prometheus 配置，固定经官方发布信息确认的稳定版本，不用 latest。所有 UI/exporter 端口回环绑定。当前存在 Docker CLI，但 `docker version` 无法连接 daemon；目前证据只能写 local stack not launched。

loopback management 与 Docker scrape 有实际网络约束：不能默认宣称 host.docker.internal 可访问只绑定 127.0.0.1 的 Host 服务。文档给出经过实测的 Docker Desktop host networking 配置（需用户开启相关支持）或明确可用的本机 Prometheus 方案；不能为 Docker scrape 把 Actuator 改为 0.0.0.0。代码不含 macOS 特判。

为 runagent.agent/workflow/workflow.node 配置 percentile histogram。文档解释 Logs 是具体事件、Metrics 是总体趋势、Traces 是单次执行的耗时链；P95 是 95% 请求耗时不超过该值，不是 max。给出基于真实 bucket 名称的 histogram_quantile 示例，缺 bucket 不宣称可计算可信 P95。100% sampling 只用于本地学习，不作为高流量生产建议。

## 验证与交付

普通 test 覆盖指标边界（空结果、无标签、多相关、rank 1/2/5、重复 ID）、多允许工具路径、顺序与参数规范化、报告隐私/聚合、baseline 不兼容、live/Judge 默认关闭。

SimpleMeterRegistry / TestObservationRegistry 覆盖 Agent 3 calls 只记录一次 value=3、COMBINED 五节点、失败仅已开始节点、RAG 3/0 命中与不可用分类、最终全部 Meter tags 不含动态 ID/问题。实际 similaritySearch 产生 VectorStore Observation；Stub ChatModel 调用 Analysis 产生 ChatClient Observation，且无 Tools/Memory/QA Advisor。读取真实配置绑定验证敏感采集关闭。Management 随机端口测试验证允许和禁止的 endpoint。

实施后执行 test、build、evaluation、默认关闭的 liveEvaluation、git diff --check；检查新依赖 runtimeClasspath。真实 LLM、四书检索与 Jaeger/Prometheus 属于独立验收证据，只在配置与服务实际可用时执行；没有执行的项目明确写 SKIPPED/未启动，不以 bean 存在或 context 启动代替真实验收。

更新 README、AGENTS 当前阶段和 roadmap；新增 docs/evaluation.md、docs/observability.md、docs/v0.8-verification.md，记录实际基线、最终测试数量、报告指标、观测名称与实际 Span 树/限制。只报告真实证据，不生成真实质量结论。不自动 commit、push、tag 或 release。
