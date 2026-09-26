# RunAgent

RunAgent 是 Watson 的个人跑步 AI 后端和 Java / Spring AI 学习项目。当前版本 **v0.2** 在 v0.1 的 Chat、SSE 和 Structured Output 基础上，增加一个只读 Running Tool。项目与 [Watson Running](https://github.com/bells/watson-running) 保持独立仓库和部署单元。

## v0.2 能力

- `POST /api/chat`：普通 Chat，可由模型选择 `getRunningSummary`。
- `GET /api/chat/stream`：SSE Streaming Chat，同样可使用该 Tool。
- `POST /api/intent`：保留 v0.1 Structured Output，不注册 Running Tool。
- `getRunningSummary(startDate, endDate)`：读取可配置的 `activities.json`，由 Java 验证参数、筛选 Run 并聚合真实统计。

```text
User → RunAgentService → ChatClient → LLM
                                  ↓ Tool Call Request
                         Spring AI ToolCallingAdvisor
                                  ↓
                         RunningTools → RunningDataService → activities.json
                                  ↓ Tool Result
                         LLM → Final Answer
```

LLM 选择是否调用 Tool 并生成日期参数；Java 执行工具、校验与统计。v0.2 仍不是完整的多步骤 Agent。具体过程见 [Tool Calling Flow](docs/tool-calling-flow.md)。

## 技术栈

Java 21、Gradle Wrapper 9.7.1、Spring Boot 4.1.1、Spring AI 2.0.1、Spring WebFlux / Reactor、Bean Validation 和 JUnit 5。通过 `./gradlew` 运行。

## 配置与启动

模型供应商为 DeepSeek。不要把真实密钥写入仓库：

```bash
export DEEPSEEK_API_KEY="your-api-key"
export DEEPSEEK_MODEL="deepseek-v4-pro"
export RUNNING_DATA_PATH=/absolute/path/to/watson-running/src/static/activities.json
./gradlew bootRun
```

`DEEPSEEK_BASE_URL` 可覆盖默认的 `https://api.deepseek.com`。`RUNNING_DATA_PATH` 对应 `run-agent.running-data.path`，必须指向生成后可读的 JSON 数组文件。源码仓库中的文件可能为空；未配置、文件缺失、空文件或内容损坏时，Tool 会报告数据无法读取，不会生成虚构统计。每次 Tool 调用重新读取文件，生成文件更新后无需重启。当前文件约 1.3 MB，这种简单策略足够。

仓库提供 `application-local.yml.example`；本地可复制为被 Git 忽略的 `src/main/resources/application-local.yml`，再用 `--spring.profiles.active=local` 启动。真实密钥和路径不要提交。

```bash
./gradlew test
./gradlew build
```

## API 示例

个人数据问题会让模型选择 Tool：

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"我今年跑了多少公里？"}'
```

通用知识问题应直接回答，不需要 Running Tool：

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"什么是LSD长距离慢跑？"}'
```

普通 Chat 返回 `{"content":"..."}`。Streaming 使用 `curl -N 'http://localhost:8080/api/chat/stream?message=hello'`，响应为 `text/event-stream;charset=UTF-8`，正常片段为 `token` 事件；流开始后的错误为安全的 `error` 事件。客户端断开会传播取消信号。`/api/intent` 继续接受 `POST {"message":"帮我分析最近一个月的跑步情况"}` 并返回 `RunningIntent`。

观察服务日志中的 `AI chat request started`、`tool=getRunningSummary phase=SELECTED`、`phase=EXECUTING startDate=... endDate=...`、`status=SUCCESS/FAILED` 与最终 AI 完成记录。日志不包含活动明细、GPS、Polyline、消息正文或密钥。

## 数据与校验

数据源只映射 `run_id`、`type`、`distance`、`moving_time` 和 `start_date_local`。仅 `type=Run` 计入统计；日期范围 `[startDate, endDate]` 两端都包含。Tool 参数必须是 ISO `yyyy-MM-dd`、起始日不晚于结束日、最多 **366 个自然日**。`moving_time` 解析为秒，距离从米累加后转换为公里；这些确定性计算均由 Java 完成。无匹配 Run 时返回零值 Summary。源文件不可用或数据格式错误时返回安全错误，不把文件路径或解析细节交给模型。Tool 结果仅包含起止日期、跑步次数、总距离公里和总时长秒数。

## Tool Calling 与 Structured Output

| 能力 | 流程 | 解决的问题 |
| --- | --- | --- |
| Structured Output | LLM → `RunningIntent` Java 对象 | 模型最终返回什么结构 |
| Tool Calling | LLM → Tool Call → Java 执行 → Tool Result → LLM | 模型何时使用外部能力 |

`/api/intent` 继续使用 `ChatClient.call().entity(RunningIntent.class)`；Tool 只提供给 Chat 路径。`RunAgentService` 集中管理 ChatClient，Controller 只适配 HTTP。同步模型调用在 WebFlux 的 `boundedElastic` 上执行；Streaming 保留 Reactor `Flux` 链路。

## Roadmap

```text
v0.3 Agent Loop
v0.4 Memory
v0.5 RAG
v0.6 MCP
v0.7 Evaluation / Observability
v0.8 Workflow / Multi-Agent
```

本阶段不引入数据库、RAG、MCP、自定义 Agent Loop 或更多 Running Tool。跨仓库边界见 [Watson Running Integration Boundary](docs/watson-running-integration.md)；代码 Agent 约定见 [AGENTS.md](AGENTS.md)。
