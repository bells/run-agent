# RunAgent v0.10 — Performance & Cost Governance

## 为什么治理

Agent能工作之后，模型调用、反复输入的Context、Tool Loop与最终综合形成主要时间和费用。目标是在质量约束下减少延迟/Token/模型费用，而不是只减少Java方法执行时间。

流程：Baseline → Measure → Find Critical Path → Optimize → Measure Again → Quality Gate。先过Evaluation，再接受性能改进；如果没有真实改善，明确“No measurable improvement”。

## v0.9真实Baseline与Critical Path

起点`f0c8fff1af2136ff62a762988936822c635f677e`（0.9.0-SNAPSHOT），2026-11-15重新验证225 tests、0 failures、0 errors、2 skipped，Evaluation27/0。历史实测保留在[v0.9-verification](v0.9-verification.md)末尾：

| 阶段 | 真实耗时 |
| --- | ---: |
| 完整Running + Knowledge + Weather | 122532ms |
| Knowledge模型 | 56170ms |
| Supervisor Synthesis | 23342ms |
| 上述两个模型合计 | 79512ms，约64.89% |

这是2026-11-08实验E的真实历史测量，不是本次v0.10的live结果。没有v0.9逐轮Usage记录，不能补造历史Token/调用数/成本。先调查Knowledge输入与模型耗时、Synthesis延迟、重复Router Context，再考虑模型选择；不能拿合成基准与122s直接计算加速。

```text
Total Latency
├── Model Latency
│   ├── Supervisor Route rounds
│   ├── Running model rounds
│   ├── Knowledge model
│   ├── Weather model rounds
│   └── Synthesis model
├── Tool / Retrieval Latency
└── Application / Graph Latency
```

当前全部顺序执行，关键路径就是Routes + Specialists + Synthesis。`runagent.multi_agent.specialist`/Snapshot stageDuration包含模型、工具和检索，modelDuration是其子集。用Trace进一步区分模型慢、检索慢、工具慢；不要把重叠的Stage与Model时间相加。

## Stage Model Policy

| ModelStage | BASELINE | OPTIMIZED | Optimized output cap |
| --- | --- | --- | ---: |
| SUPERVISOR_ROUTE | DEFAULT | FAST | 256 |
| RUNNING_ANALYST | DEFAULT | FAST | 800 |
| KNOWLEDGE_COACH | DEFAULT | DEFAULT | 1000 |
| WEATHER_ADVISOR | DEFAULT | FAST | 800 |
| SUPERVISOR_SYNTHESIS | DEFAULT | STRONG | 1200 |

`ModelRoutePolicy`只接收固定enum，名字来自受信配置，问题、HTTP字段、Supervisor task或Report都不能指定模型。所有tier通过同一个现有`AI_GATEWAY_BASE_URL`，只改变请求model name。没有Provider发现、跨Provider failover或重试。

Router主要分类/委派；Running与Weather主要选Tool并解释事实，所以是FAST候选。Knowledge需要理解检索证据，保留DEFAULT。Synthesis跨证据综合和冲突处理，是STRONG候选。候选不等于更好：FAST误路由应回退DEFAULT，STRONG若更慢更贵，必须证明质量收益。

```bash
export PERFORMANCE_MODE=OPTIMIZED       # 默认BASELINE
export AI_FAST_MODEL='<configured-fast-model>'
export AI_STRONG_MODEL='<configured-strong-model>'
# DEFAULT沿用AI_GATEWAY_MODEL以及现有legacy配置回退
./gradlew bootRun --args='--server.address=127.0.0.1'
```

FAST/STRONG未配置时解析DEFAULT，启动日志同时显示modelTier与resolvedTier；计价也用resolvedTier，避免给DEFAULT误套FAST价格。默认不是Java硬编码模型，而是现有`spring.ai.openai.chat.model`的生效配置。启动只显示pricingStatus，不输出价格表。

BASELINE保留DEFAULT与原Context上限。两个mode都加入global output cap、call budget与soft budget，这是相对v0.9的明确安全变化；不能宣称逐字等同旧版。OPTIMIZED才使用更小Stage output和Context实验值。

### 先隔离Context变量的本地实验

2026-11-15真实手动实验确认：deepseek-v4-flash在当前三源问题上使用默认OPTIMIZED输出限制，Running/Weather分别在800、Knowledge在1000 Token处失败，最终没有成功Evidence。该模式在此case上尚未通过质量门槛，较短的失败耗时不能当加速收益。全局上限即使设为3000，实际仍是`min(global, stage)`。

本机`application-local.yml`已改为同模型、同输出额度的保守对照；这是被忽略的实验配置，没有更改仓库默认策略：

```yaml
run-agent:
  performance:
    mode: OPTIMIZED
    max-output-tokens-per-call: 3000
    soft-total-tokens: 60000
    stage-output-limits:
      supervisor-route: 3000
      running-analyst: 3000
      knowledge-coach: 3000
      weather-advisor: 3000
      supervisor-synthesis: 3000
```

在FAST/STRONG仍回退DEFAULT时，最初这轮只改变Router报告投影与Knowledge Context预算。取得完整结果后，再每次只收紧一个Stage输出额度或改变一个模型配置，同时观察质量。上述3000/60000也是实验值，不是最佳值；修改文件后需重启IDEA应用。启动日志现在显示每个Stage生效的maxOutputTokens，任务结束显示固定枚举budgetReasons，不输出敏感内容。

后续真实复测表明，同样3000的上限仍不能保证每次完整：Knowledge和Synthesis再次触顶失败。因此现已在OPTIMIZED增加固定的简短输出指令：Knowledge只给最多三个相关原则及限制，目标600中文字符；Synthesis最多五条，目标900中文字符，不生成长表格/逐日周计划，不自行推算数值训练处方。BASELINE提示词原样保留，没有新增Summarizer或额外模型调用。这属于人工定义的输出格式约束，不是自动Prompt优化；后续对照的处理变量现在是Context加简短输出策略，不能再把差异只归因于Context。

这些是软格式目标，硬生成上限仍由原BudgetedChatModel执行；离线契约测试不能证明真实模型一定遵守。真实复测仍需确认完整性、证据忠实度和建议质量，不能靠截断后补写或自动重试取得表面成功。

### 按阶段配置推理策略

后续真实样本确认Knowledge检索成功，但模型输出3000 Token中2829属于推理，最终OUTPUT_LIMIT；可见输出仅276字符。简短正文指令不能可靠限制推理消耗。新增可选受信配置，仅OPTIMIZED生效，BASELINE及其他API保持供应商/共享模型原选项：

```yaml
run-agent:
  performance:
    stage-reasoning-effort:
      knowledge-coach: ${PERFORMANCE_REASONING_EFFORT_KNOWLEDGE_COACH:PROVIDER_DEFAULT}
```

枚举为PROVIDER_DEFAULT、NONE、LOW、HIGH、MAX。默认PROVIDER_DEFAULT不覆盖任何推理选项；其他值使用Spring AI 2.0.1 OpenAiChatOptions标准reasoningEffort，在每次请求的新副本上设置，不修改共享ChatModel。若继承extraBody中的reasoning_effort或thinking，显式策略会同步覆盖这些冲突键，保留无关配置；不根据用户问题推断策略。非OpenAI选项遇到显式覆盖会安全失败。网关/模型必须支持相应值，没有自动兼容降级或重试。

2026-11-15核对[DeepSeek Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/)与[Chat Completion](https://api-docs.deepseek.com/api/create-chat-completion/)官方说明：reasoning_effort=none关闭推理，显式thinking.type优先于该字段。不能把这一供应商行为当成所有OpenAI-compatible模型的通用保证，也不改变已配置的模型名称。

本机忽略的application-local.yml现仅将knowledge-coach设为NONE，输出3000与soft total60000保持。其他阶段仍PROVIDER_DEFAULT；这是待真实复测的实验，不是已证明的质量/性能改进。后续对照包含Context、固定输出/工具规划指令以及此推理策略，不能再声称纯Context单变量。缺少知识报告只能说明本次无可用分析，不可据此断言检索或索引失败。

## Spring AI 2.0.1实现

实际核对缓存中2.0.1源码及编译：`ChatModel.getOptions()`、`ChatOptions.mutate().model(...).maxTokens(...).build()`。OpenAiChatOptions的mutate保留类型、工具、ToolContext、structured-output选项；单次Prompt覆盖模型与输出Token，OpenAI适配器生成请求中的`model`和`max_tokens`。未升级框架。

每Stage各有`BudgetedChatModel`，delegate仍是共享ChatModel；Supervisor拆为routeClient/synthesisClient。ToolCallingAdvisor的同步循环每次`chain.copy(this).nextCall(...)`都经过decorator，因此Model → Tool → Model → Tool → Model计3次。不能再对最终聚合ChatResponse重复计Usage。

wrapper先检查/预留，再调用delegate；finally记录耗时、状态、Usage并reconcile。Provider失败仍消耗一次调用并记录FAILED/UNKNOWN，不保存异常内容。输出finish_reason=length/max_tokens/max_output_tokens或已知completion超过cap，安全失败，不能拿截断Router决定当有效输出。Provider已发生的消费无法事后撤回。

## ExecutionBudget：Hard与Soft

| 约束 | 默认 | 性质 |
| --- | ---: | --- |
| Deadline | 180s，沿用MULTI_AGENT_TIMEOUT | Hard停止后续操作；底层取消best effort |
| 模型调用 | 16，PERFORMANCE_MAX_MODEL_CALLS | Hard，调用前预留 |
| 单次输出 | 1500，PERFORMANCE_MAX_OUTPUT_TOKENS_PER_CALL | Hard请求上限，Stage限制再取min |
| 全任务Token | 30000，PERFORMANCE_SOFT_TOTAL_TOKENS | Soft，按返回的真实Usage结算 |
| 委派 | 3，沿用MULTI_AGENT_MAX_DELEGATIONS | Hard，不创建第二套计数 |

16/1500/30000与各Stage上限都只是初始实验值，不是最佳值。降低全局输出上限会夹紧Stage值。Stage配置在`run-agent.performance.stage-output-limits`，可由`PERFORMANCE_ROUTE_OUTPUT_TOKENS`、`PERFORMANCE_RUNNING_OUTPUT_TOKENS`、`PERFORMANCE_KNOWLEDGE_OUTPUT_TOKENS`、`PERFORMANCE_WEATHER_OUTPUT_TOKENS`、`PERFORMANCE_SYNTHESIS_OUTPUT_TOKENS`覆盖。

每执行一个Budget，跨Handoff不重置。复用现有Control检查deadline/取消，不另起Performance计时限制。预算/Collector只经本地RunnableConfig metadata传递，每节点try/finally安装/清除ThreadLocal；不依赖graph.invoke所在的线程，不使用executionId→Budget的全局Map。MCP仍noOp converter与空metadata。

非Synthesis调用在remainingCalls<=1时被阻止，最后一次只留给Synthesis。Router之前和之后都检查：有成功报告就FINISH，避免Router耗掉倒数第二次后再开始Specialist。内部Tool Loop耗尽也不能绕过wrapper；未完成报告不是成功证据。没有任何成功报告时返回安全503 `MULTI_AGENT_BUDGET_EXCEEDED`，不能靠常识fallback。正常全部失败仍是INSUFFICIENT_EVIDENCE。

没有通用精确预调用Tokenizer，Prompt长度与模型Tokenizer不同。Token不是假装精确的hard reservation；每轮结束累加已知Usage，超过软预算则softBudgetExceeded，停止可选工作，允许已有证据的最终Synthesis。最后一次Synthesis自身仍可能使总量更高，这就是soft语义。

## Usage Collection与UNKNOWN

每轮安全记录：executionId、stage、resolved tier、configured model、input/output/total、duration、status、usageStatus、reasoningEffort；cache counts可保留，但native Usage对象立即丢弃。reasoningEffort是请求策略，reasoningTokens是实际返回的推理明细，二者不互相推定；NONE不意味着可以将缺失明细记为0。

`EmptyUsage`、缺字段、负数、不一致total、没有输入消费证据均UNKNOWN，tokens=null。Spring AI DefaultUsage会把null补零，所以没有原始显式证据的零completion保守标UNKNOWN；OpenAI SDK原始CompletionUsage能证明显式0时可计入。无法逆向恢复框架已丢失的原字段，宁可降低完整度。

混合Usage保留knownInput/Output/Total小计，但usageComplete=false、tokenEnforcement=DEGRADED、整单Cost UNKNOWN。后续已知消费仍可触发soft stop；硬Deadline/Calls/Output/Delegations继续有效。UNKNOWN不是免费或零Token。

逐轮诊断额外记录固定枚举finishReason（STOP/OUTPUT_LIMIT/TOOL_CALLS/FILTERED/UNKNOWN）、outputChars（可见输出的Unicode code point数）及reasoningTokens（Provider原始CompletionUsage提供时）。推理明细必须满足0≤reasoning≤completion；缺失/非法为UNKNOWN，不推测、不读取或记录推理正文。reasoningTokens是completion的子集，不再次累加Token或费用；明细缺失不会把本来已知的完整总Usage降为UNKNOWN。原始Provider finish reason不进入日志，未知值统一UNKNOWN，避免自由字符串携带正文。

`PerformanceSnapshot`在请求finally生成：mode、totalLatency、modelCalls、stageBreakdown、已知小计、unknownCalls、usageComplete、tokenEnforcement、estimatedCost、softBudgetExceeded、budgetReasons与安全逐轮records。只供日志/观测/benchmark；公开MultiAgentResponse仍只有原五个字段。没有持久化成本数据库或历史Execution注册表。

## CostEstimator：Estimated Model Cost

```text
Request → Model Calls → input/output Usage → Configured Price → Estimated Model Cost
```

只计算Chat Model的input/output费用；不包含MCP、Weather API、CPU、网络、电费或Ollama Embedding。没有实时厂商价格抓取、FX、账单或精确结算保证。

```yaml
run-agent:
  performance:
    pricing:
      currency: USD
      tiers:
        default:
          input-per-million: ${DEFAULT_MODEL_INPUT_PRICE_PER_MILLION:-1}
          output-per-million: ${DEFAULT_MODEL_OUTPUT_PRICE_PER_MILLION:-1}
        fast:
          input-per-million: ${FAST_MODEL_INPUT_PRICE_PER_MILLION:-1}
          output-per-million: ${FAST_MODEL_OUTPUT_PRICE_PER_MILLION:-1}
        strong:
          input-per-million: ${STRONG_MODEL_INPUT_PRICE_PER_MILLION:-1}
          output-per-million: ${STRONG_MODEL_OUTPUT_PRICE_PER_MILLION:-1}
```

币种可由PERFORMANCE_CURRENCY指定，只使用该币种。BigDecimal公式：`inputTokens × inputPrice / 1,000,000 + outputTokens × outputPrice / 1,000,000`。例：1,000,000×2 + 500,000×4，按百万单位得到4。这是测试价格，非厂商报价。

任一Usage/输入价/输出价未知或为负数，整单UNKNOWN；不能把已知小计当完整Cost。明确价格0是已配置免费模型，和缺价不同。存在非零cache read/write且无独立cache价格时，保守UNKNOWN；不把缓存Token套普通输入价伪装精确。价格由维护者核对，变化后重新配置。

## Context Engineering与Tool-first

| Context | BASELINE | OPTIMIZED |
| --- | ---: | ---: |
| 每个Routing报告 | 原bounded report，默认2500 chars | 最多600 chars |
| Synthesis报告 | 完整bounded report，默认2500 | 同左 |
| Knowledge Evidence | 12000 chars | 9000 chars |
| Specialist绝对报告上限 | 默认2500 chars | 同左，以输出cap促使自然简短 |
| Task绝对上限 | 默认2000 chars | 同左 |

Context配置：PERFORMANCE_ROUTING_REPORT_CHARS、PERFORMANCE_KNOWLEDGE_EVIDENCE_CHARS。字符只用于尺寸控制，不用char/4冒充Token。Knowledge保持原检索TopK/threshold，按rank加入Document、保留source title，最后一篇必要时安全截断且不拆Unicode surrogate pair。先减少重复输入而非削减TopK，以免直接损伤Recall。预算可能截掉后部证据，仍需Quality Gate。

Router只判断还缺什么；最终综合才需要完整成功Report。Prompt继续要求短的actionable task，避免把全部报告复制进去。不新增Summarizer、Reflection、Critique、Judge或重写模型。

Running/Weather保留三项最小Tool、日期/location/坐标guard及真实Tool证据要求。模型选择Tool/参数、解释结果并给短报告，不能用内部常识编造业务事实。Tool-only was evaluated, but is only safe when the tool plan is deterministic. 当前Request只有自由问题+日期/地点，无法确定Summary/Recent/PB或天气工具计划，故未实现Direct Tool-only；不使用contains、正则或Java NLP猜意图。

后续OPTIMIZED Running增加固定的工具规划指令：对单个权威日期窗口通常只需一次Summary，活动细节按需查询RecentRuns，复用成功证据，不为泛化训练建议默认枚举四种PB；日期提示会覆盖工具日期，不应反复请求其他区间进行无效比较。证据足够就停止调用并写短报告。这只是模型软指令，不是确定性Tool-only计划、缓存、重试或硬编码NLP，不能据此保证实际调用次数；现有单工具4次/总8次硬限制保持。

Advisor工具上限与任务级模型调用上限不同。达到工具限制时，模型round本身仍可能SUCCESS/TOOL_CALLS，已执行的工具也可能全部SUCCESS，但Advisor会返回toolCallLimitExceeded，Specialist不能发布有效报告。现在增加固定安全日志`phase=REPORT status=FAILED reason=TOOL_CALL_LIMIT successfulToolCalls=...`。`budgetReasons=[]`只代表任务级模型/Token/输出/deadline未触发，不代表Advisor工具次数没有触发。报告未形成不等于底层个人数据不存在，最终回答应说明本次没有可用分析报告。

## Benchmark、Baseline vs Optimized与Quality Gate

```bash
./gradlew test
./gradlew build
./gradlew evaluation
./gradlew performanceBenchmark
./gradlew livePerformanceBenchmark  # 默认SKIPPED
```

performanceBenchmark在test sources，不打入应用JAR，不是test/build依赖。复用版本化Evaluation七个Multi-Agent case、独立路由脚本、合成Running文件、真实合成向量检索和固定Weather callbacks，执行真实Graph/Clients/Tool Loop。每Profile同样七个case，顺序BASELINE再OPTIMIZED；每case新Budget，记录真实执行集合与成功集合。故障注入前也验证Tool参数，工具型角色必须有实际Tool尝试，不能以没有调用工具的失败冒充依赖故障。

离线模型每轮提供固定100/20/120合成Usage、2ms固定delay和所有tier输入2/输出4的合成价格。两个Profile相同fixture消费是正常结果；实际Java/JIT开销不同不代表模型路由更快。`benchmarkType=SYNTHETIC_CONTRACT`不能称真实模型性能。

报告在`build/reports/run-agent-performance/report.json`、`report.md`，含timestamp/version、数据集版本与checksum、路由及fallback、每case安全ID/状态/指标、成功率、委派/完成/Tool/隔离准确率、平均/P50/observedP95、calls/request、input/output/total、Usage/Cost完整度、平均Cost、Stage总耗时和模型耗时。UNKNOWN仍为null/UNKNOWN。Stage breakdown聚合为所有测量请求总量，均值只用于标明/request的指标。

```bash
# 仅显式环境，不读取application-local.yml/.env/真实书籍索引
export LIVE_PERFORMANCE_BENCHMARK_ENABLED=true
export PERFORMANCE_BENCHMARK_RUNS=3  # 1–10；每case每mode额外1 warmup
# 需已配置AI_GATEWAY_BASE_URL / AI_GATEWAY_API_KEY / AI_GATEWAY_MODEL
# 可选AI_FAST_MODEL / AI_STRONG_MODEL及人工核实的价格
./gradlew livePerformanceBenchmark
```

Live使用相同合成Running/RAG/Weather，只把模型换成同一Gateway的真实模型，`maxRetries(0)`；不访问个人MCP、私有四书、本地private golden或真实天气地点。每case沿用统一deadline，`PERFORMANCE_BENCHMARK_TIMEOUT_SECONDS`默认180。默认7×2×(1+3)=56个Multi-Agent请求，存在多轮模型费用，必须显式开启。

Live可显式设置`PERFORMANCE_REASONING_EFFORT_KNOWLEDGE_COACH=NONE`（其他Stage使用对应ModelStage枚举后缀）；只影响OPTIMIZED，BASELINE忽略。离线Benchmark忽略这些环境变量。报告路由包含请求的reasoningEffort，便于识别对照处理变量；它不证明真实网关已遵守或质量保持。

Live `benchmarkType=LIVE_MODEL`，报告失败保留reasonCode，无异常/正文dump，不让build依赖它。不默认启用Judge。3次是小样本，observedP95是样本统计，不是Production P95。历史真实实验授权不自动延续到新的私有数据实验；若做真实两模式人工对照，需本次明确授权，并固定相同问题/日期/地点、接近时间，记录元数据而不保存Evidence/Answer。

Evaluation判断“结果质量怎样”，Benchmark判断“同样质量需要多久/多少Token/多少成本”。脚本契约通过只排除确定性回归，不能证明真实模型groundedness、训练科学正确或用户体验。优化结果必须同时给Quality/Latency/Tokens/Cost；本次没有真实live比较，不能宣称提升比例。

```text
Quality
  ▲     acceptable solutions
  │       (quality gate passes)
  └──────────────────────────► Cost / Latency
在质量满足要求的区域内，寻找更合理的耗时与费用。
```

## Observability与Cardinality

新增`runagent.performance.model`每轮Observation，低基数mode/stage/tier/reasoning_effort/outcome/usage.status；model与executionId只high-cardinality。`runagent.performance.model.calls`按每次实际尝试计数；`runagent.performance.usage.calls`记录完整度；`runagent.performance.budget.exhausted`每任务每固定reason一次。原有Stage计时和内置gen_ai.*继续使用，不重复自定义Token histogram或币种cost meter。Counter在任务finally按records刷新，进行中的底层观测仍由内置模型Span提供。

禁止把question、location、model response、tool args/result、report、书页或任意executionId放Meter tag。日志/Span/报告不含Prompt、Completion、用户问题、Report、个人跑量、RAG证据、Weather payload、工具参数/结果或API Key。保留v0.8全部privacy convention/sanitizer与false配置；不能开DEBUG查看Token。

## 当前局限与未来演进

- No parallel agents / parallel tool optimization；先治理调用与Context，避免并行掩盖过度调用。
- No multi-provider routing/failover、dynamic discovery、新Agent/Tool/MCP/框架。
- No exact pre-call tokenizer；Total Token是soft，Provider不返回Usage时治理降级。
- No automatic price fetch/货币转换；价格需要人工维护，估算不等于账单。
- No persistent cost database、production billing、distributed rate limiter。
- No semantic/prompt/Redis cache、fine-tuning、automatic prompt optimization、retry或speculation。
- 网络取消best effort，已发出的模型请求可能继续消费；deadline禁止后续调用，不保证立刻停止供应商。
- Context/Output截断与模型换tier可能降低质量，只有实际Evaluation与Live对照才能接受优化。

未来只在测量和质量证据支持时考虑收紧预算、调整Stage策略或并行；不把这些扩展带入v0.10。
