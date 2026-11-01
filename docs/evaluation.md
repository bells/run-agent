# RunAgent v0.8 Evaluation

Evaluation 回答“系统做得对不对”；Observability 回答“执行中发生了什么”。Unit Test ≠ Evaluation，但可以组合：单元测试确定性地验证评分公式、执行边界与报告；Evaluation 在一批版本化 case 上衡量系统表现。LLM 自然语言答案不应通过固定整段字符串相等判断质量。

## 三层质量判断

1. Deterministic Evaluation：Java 判断 Tool 名称/参数、Running 计算、Workflow 路径、检索排名。
2. LLM-as-a-Judge：判断 Answer Relevance、给定证据上的 Groundedness；默认关闭，只属于离线评估管线。
3. Human Evaluation：人工判断训练建议的实用性、自然度、专业性和跑步经验适配。本阶段只说明方法，没有 Human Review 系统。

推荐每次变更遵循 Build → Evaluate → Observe → Find Problems → Improve → Regression Evaluate → Release。报告是定位回归的证据，不能替代真实模型及人工验收。

## 命令与隔离

```bash
./gradlew test
./gradlew build
./gradlew evaluation
./gradlew liveEvaluation
```

前两项不执行评估任务，不依赖 LLM、8090 Server、公共 Weather、Ollama 或真实书籍。评估工具代码位于 test sources，不打入应用 JAR。JavaExec Runner 使用 test runtime classpath，直接调用 Java 组件，不向本应用 HTTP API 发请求。

`evaluation` 完全离线，输出 `build/reports/run-agent-evaluation/report.json` 与 `report.md`；低于离线门槛会在报告写完后让 task 失败。检索/统计的执行异常也生成安全失败case，覆盖旧报告后再触发gate。`liveEvaluation` 默认输出 `Live evaluation disabled.`，不属于 build；显式打开但缺少 AI_GATEWAY 配置会 SKIPPED，不创建模型请求。

```bash
./gradlew evaluation -Devaluation.threshold.precisionAtK=0.8
```

可覆盖工具选择/参数、route、recall、precision、mrr 六个聚合阈值，值必须在 [0,1]。默认 Tool Selection、Tool Arguments、Workflow Route、Recall、MRR 为 1.0，合成 Precision 为 0.8。它们是合成回归门槛；没有任何“真实模型必须达到 95%”的生产承诺。

## Dataset 与证据类型

`src/test/resources/evaluation/` 的六个 JSON 文件都含 `datasetVersion=v1`：

| 文件 | 内容 |
| --- | --- |
| tool-cases.json | 12 个 case：Summary、Recent、四种 PB、No Tool、两区间比较、多步调用 |
| model-scripts.json | 与期望路径分开维护的离线模型响应脚本 |
| synthetic-activities.json | 明确合成的活动，无 GPS、Polyline 或真实个人数据 |
| rag-cases.json | 3 个有标签检索问题和 1 个零召回问题 |
| synthetic-knowledge.json | 轻松跑、阈值、长距离、厨房噪声，均为自创短文本 |
| workflow-cases.json | 三种 mode 的预期实际节点路径 |

全部日期为固定绝对日期，Prompt 中日期也固定为 2026-04-01。未来添加 today/yesterday case 必须固定 Clock，不能随着重跑年份改变题意。每次报告记录各文件版本、SHA-256 和 suite checksum。

离线 Tool 使用脚本 ChatModel → 真实 RunAgentService/ToolCallingAdvisor → RecordingToolCallback → 真实 RunningTools/RunningDataService。另有手工算出的合成公里/时长/PB 检查。实际调用从 callback 捕获，不能直接把 expectedTools 当实际结果。

**SCRIPTED_MODEL_CONTRACT** 只衡量脚本模型下的调用契约回归，不衡量模型理解问题和选择工具的能力。**FAKE_EMBEDDING** 使用独立文本特征向量 → 真实 SimpleVectorStore → KnowledgeSearchService 检索，不读取相关标签生成排名；它不证明真实 bge-m3 的效果。**DETERMINISTIC_GRAPH** 执行真实 StateGraph 和节点，目标是三条路由准确率 100%。

## 指标定义

Tool Selection Accuracy = 完整匹配一个允许 Tool 路径的 case 数 / 全部 Tool case 数。允许 `allowedToolSets` 多路径；默认顺序无关，`ordered=true` 严格顺序。重复调用计数保留，两次同名 Tool 不等于一次。

Tool Argument Case Accuracy = 名称、调用数量和所有参数完整匹配任一允许路径的 case 数 / 全部 Tool case 数。JSON 字段顺序无关；数值 5 与 5.0 语义相同，字符串 "5" 不等于数值；ISO 日期按 LocalDate 比较。缺字段、多字段、错日期、多余调用都失败。No Tool case 的正确结果只能是零调用。执行异常时case仍失败，但已捕获的正确名称/参数按实际结果计分；若在任何调用记录前失败，则没有成功的选择证据，两个指标记0。

检索指标在每个 query 的 Top-K 上计算：

- Recall@K = 命中的不同相关文档数 / 已标注的全部相关文档数。
- Precision@K = 命中的不同相关文档数 / K；实际返回不足 K 也使用 K。
- RR = 第一个相关结果原始排名的倒数；无命中为 0。MRR 是有标签 query 的 RR 平均。

例如 relevant=A/B/C，Top5=A/C/X/Y/Z，Recall=2/3、Precision=2/5、RR=1。第一个相关结果位于 2 或 5 时，RR=0.5 或 0.2。重复 ID 只命中一次，保留原始排名，不能靠去重提前排名。空标签为 UNJUDGED，排除于检索指标平均；明确 `expectedNoContext` 的 case 单独计算 noContextAccuracy。

当前合成 Top2：轻松跑与阈值各有两篇相关文档，长距离只有一篇，因此理想平均 Precision@2=(1+1+0.5)/3=0.8333，而 Recall/MRR=1。这是固定 K 分母的结果。

Workflow Route Accuracy = 实际 executedNodes 与 expectedNodes 完全一致的 case 比例；顺序、长度都严格比较，目标 1.0。低于 1.0 是确定性回归，不是模型概率波动。

## Live Model 与 Judge

只复用显式环境变量，不读取被忽略的 application-local.yml 密钥，不自动创建 API Key：

```bash
export LIVE_EVALUATION_ENABLED=true
# 沿用已配置的 AI_GATEWAY_BASE_URL、AI_GATEWAY_API_KEY、AI_GATEWAY_MODEL
./gradlew liveEvaluation
```

默认只执行同一批 12 个真实模型 Tool Selection/Arguments case，使用合成 Running 数据和独立 RecordingToolCallback，不访问个人 Running MCP。报告标记 LIVE_MODEL，写到独立 `build/reports/run-agent-evaluation/live/`。模型错误、超时、质量波动均 report only，不让 build 失败。无效配置/无法写报告仍是执行错误。

每 case 默认 30s，可设 `EVAL_CASE_TIMEOUT_SECONDS`（1–300）；工具仍每项最多4次/总8次，模型回合有额外上限，SDK retries=0。deadline 与取消均是尽力而为，不能保证已经发出的 HTTP 被立即终止；超期后的模型返回不会继续启动下一步。可显式设置 `EVAL_TEMPERATURE`；未设置时报告不伪造值。Low temperature reduces randomness, but does not guarantee deterministic output。

```bash
export EVAL_JUDGE_ENABLED=true
# 可选：export EVAL_JUDGE_MODEL="another-model"
./gradlew liveEvaluation
```

`JUDGE_ENABLED` 是较低优先级兼容别名。Judge 仅处理合成知识 query/context/target answer，使用 Spring AI 2.0.1 RelevancyEvaluator 和 FactCheckingEvaluator，报告二元 answerRelevance/groundedness 和 Judge 身份。未指定独立模型时明确 `judgeEqualsTarget=true`；不声称独立判官。Judge 失败不丢弃 Tool 结果。

**LLM Judge ≠ Ground Truth**。它受 Bias、模型错误、Prompt 敏感性、自我偏好、成本、延迟和非确定性影响。FactChecking 仅判断给定 context 是否支持 answer，不能证明训练科学、安全、医学正确或世界知识完全正确。可确定的日期/参数用 Java 判断。Judge 不进入 `/api/agent`、Workflow 或线上请求。

## 本机四书人工 Golden

`.run-agent/` 已 Git ignored。用户可以人工维护 `.run-agent/evaluation/rag-golden.json`，不提交问题、正文或索引。以下是格式示例，文本和标签都是占位说明，不是真实 golden：

```json
{
  "datasetVersion": "human-v1",
  "cases": [{
    "id": "local-question-1",
    "question": "由你人工编写并核实的问题",
    "relevantDocumentIds": [],
    "expectedSourceTitle": "人工核对的来源标题",
    "expectedChapter": null,
    "expectedPage": null,
    "k": 5
  }]
}
```

可以用稳定的 `relevantDocumentIds` 替代来源标签；没有文档 ID 时只报告 localSourceHit，不能叫 document Recall。自动生成的标签只能叫 candidate labels。每个 case 必须有人类标签；无标签、重复 ID 或格式错误报告 INVALID_LABELS。

显式 live 开启后，文件存在则额外运行 **LOCAL_PRIVATE_DATASET** 检索。使用 RAG_* 环境变量加载现有索引，`reindex=false`，不下载模型、不读取书籍重建；Embedding endpoint 只允许本机回环。加载配置须与原 fingerprint 相同；不匹配只报告 unavailable，不改索引。私有 question/context 从不进入 Target/Judge。报告只保留安全 ID、数量、分数和 reasonCode，不保留来源标题、页码、问题或正文。

## 报告、比较与局限

JSON/Markdown 包含执行时间、应用版本、数据集身份、case 数量、聚合指标和每 case 的 ID/category/evidence/status/metrics/reasonCode。报告 DTO 没有 Prompt、完整答案、工具参数/原始结果、书籍 Chunk、个人跑量、密钥或异常详情。build 虽被忽略，仍按相同隐私原则设计。

可选 `.run-agent/evaluation/baseline.json` 保存人工选定的历史 report.json 副本。只有 suite checksum、executionType、metricDefinitionVersion、model/Judge/temperature 身份均一致才输出 delta；不同数据或执行类型标记 BASELINE_INCOMPATIBLE。没有自动覆盖 baseline 的命令。

Recall@K 等 Evaluation Metrics 不注册成线上 Gauge，因为线上没有 Ground Truth。运行时 RAG match count 也不是 Recall。当前没有真实 Embedding corpus 的自动 live benchmark、实验追踪平台或人工评审系统。真实结果和未执行项见 [v0.8 验证](v0.8-verification.md)。
