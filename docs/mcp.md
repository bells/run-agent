# RunAgent v0.6：MCP 学习与运行手册

## 1. MCP 解决什么问题

Tool Calling 由模型选择工具并生成参数；MCP 为 Host 和能力提供方规定统一的发现、Schema 与调用协议。模型不会直接连接 MCP Server。MCP 也不负责模型是否作出了合理的训练判断。

```text
User → LLM → Tool Call Request → RunAgent → MCP ToolCallback
     → MCP Client → MCP Protocol → MCP Server → Business Service
     → Tool Result → MCP Client → LLM → Final Answer
```

## 2. 三个角色与数据所有权

- **Host：RunAgent**，拥有 ChatClient、LLM、ToolCallingAdvisor、Memory，以及独立 Knowledge API 的 RAG。
- **Client：Host 内的 Spring AI MCP Client**，负责 initialize、tools/list、tools/call 和连接生命周期。
- **Server：独立 Running MCP Server / 第三方 Weather MCP Server**，提供各自的能力。

```text
                         RunAgent / MCP Host
              ┌────────────────┼──────────────────┐
           Memory         独立 RAG API         MCP Client
              │                │              ┌───┴──────────┐
         Conversation      Four Books      Running MCP    Weather MCP
                                               │             │
                                        RunningDataService Weather APIs
                                               │
                                         activities.json
```

v0.5：RunAgent → 本地 RunningDataService。v0.6 MCP 模式：RunAgent → MCP → 独立 Server → RunningDataService。
`RUNNING_DATA_PATH` 只由 Running MCP 进程使用；Host 不需要文件路径，也不创建本地 RunningTools 或 RunningDataService Bean。Server 不反向调用 Host，不存在循环依赖。两个应用独立 Gradle、独立 Git、独立 JVM。

## 3. Running MCP Server

同级项目 `../running-mcp-server`，Java 21、Boot 4.1.1、Spring AI 2.0.1、Gradle Wrapper 9.7.1。
使用 `spring-ai-starter-mcp-server-webflux`；默认 `protocol=STREAMABLE`、`type=SYNC`、端口8090、`/mcp`、绑定127.0.0.1，协议版本在 initialize 时协商。
WebFlux transport 和业务编程模型是两个维度。现有文件读取、过滤、聚合是同步方法，不需要为了 WebFlux 将它们改造成 Mono/Flux。

| MCP Tool | Description | Required parameters |
| --- | --- | --- |
| getRunningSummary | 真实历史跑步次数、总公里数、总秒数；比较时分段查询 | startDate / endDate，yyyy-MM-dd，含首尾，最多366天 |
| getRecentRuns | 指定范围内最近的单次跑步摘要，最新优先 | startDate / endDate / limit（1–20） |
| getPersonalBest | 标准距离附近整次活动最快平均配速，approximate，非精确 split 或赛事PB | distanceType：FIVE_K / TEN_K / HALF_MARATHON / MARATHON |

Server 使用 `@McpTool` 和 `@McpToolParam`，保留 v0.5 的 Run 过滤、距离和时长转换、日期校验、配速与±5% PB容差。原数据、GPS、Polyline、起终点不出现在结果中。

## 4. 运行两个进程

终端1：

```bash
cd ../running-mcp-server
export RUNNING_DATA_PATH="/absolute/path/to/generated/activities.json"
export RUNNING_MCP_BIND_ADDRESS=127.0.0.1
export RUNNING_MCP_PORT=8090
./gradlew bootRun
```

终端2：先配置现有 AI_GATEWAY_* 或使用被忽略的 local profile。

```bash
cd ../run-agent
export RUNNING_TOOL_PROVIDER=mcp
export RUNNING_MCP_URL="http://127.0.0.1:8090"
./gradlew bootRun --args='--spring.profiles.active=local,mcp --server.address=127.0.0.1'
```

Weather 未启用时不会建立第三方 Weather MCP 连接。测试和默认启动均 `enabled=false`，无需任何 MCP Server。

## 5. Local 与 MCP

`RUNNING_TOOL_PROVIDER=local` 为兼容模式，也是默认值；使用现有三项本地工具，可选叠加 Weather。
`RUNNING_TOOL_PROVIDER=mcp` 为 v0.6 目标架构，必须启用 mcp profile 和 MCP client；配置不一致明确启动失败。
本地工具和数据 Service 仅在 local 模式注册。单一 McpToolFilter 在 local 模式拒绝远端 Running Tools，因此不会同时向模型暴露两套同名跑步能力。

## 6. Discovery 与显式注册

Host 使用 `spring-ai-starter-mcp-client-webflux`。Starter 创建命名 Streamable HTTP transports / SYNC clients 并负责关闭。
`initialized=false` 避免 Starter 一次连接失败阻断全部启动；自动 `toolcallback.enabled=false` 避免绕过本项目过滤和 Trace。

`McpCatalog` 在启动时对每条连接分别 initialize，以官方 `SyncMcpToolCallbackProvider` 执行发现、过滤和适配。单条连接失败只移除该连接的工具。回调快照被显式交给 ChatClient：

```java
chatClient.prompt()
    .system(prompts.agentSystemPrompt())
    .user(message)
    .tools(tools) // ToolCallbackProvider：Spring AI 2.0.1 的统一入口
    .toolContext(Map.of(AgentExecutionTrace.CONTEXT_KEY, trace))
    .call().chatResponse();
```

`ToolCallbackProvider` 将 MCP Tool 适配为 Spring AI 工具体系；加 Starter 并不等于模型自动获得所有能力。2.0.1 的旧 `toolCallbacks(...)` 已标记移除，统一使用 `.tools(...)`。
本项目不手写 JSON-RPC，不在每次 HTTP 请求执行 tools/list。当前为启动时快照；启动后停机在 tools/call 处安全降级，启动时不可用或工具集合更新后需重启 Host 重新发现，不实现自动重连/动态注册。

## 7. 第三方 Weather MCP

选用 [cyanheads/open-meteo-mcp-server](https://github.com/cyanheads/open-meteo-mcp-server)，直接消费其公开 Streamable HTTP 服务，不复制源码、不绕过 MCP 调 REST。
2026-10-17 核实 README 和 live initialize：Server 0.4.1，协商 MCP 2025-11-25。README 宣称12项，实际 tools/list 返回11项；本项目只暴露3项，不以文档数量代替发现结果。
非商业场景无需 API Key；商业使用遵循 Open-Meteo 条款。公共服务为社区维护、外部网络依赖，无 SLA，只适合学习和 Demo。

```bash
# 从上游 README 获取当前 public hosted Streamable HTTP URL，包含 /mcp。
export WEATHER_MCP_URL="<current-public-hosted-streamable-http-url>"
export RUNNING_TOOL_PROVIDER=mcp
./gradlew bootRun --args='--spring.profiles.active=local,mcp,weather-mcp --server.address=127.0.0.1'
```

Weather profile 未提供有效绝对 HTTP(S) URL 时启动失败，不连接默认或猜测的地址。不接受 URL 用户凭据、query 或 fragment。
2.0.1 Transport 的 endpoint 不能为空；Weather 配置将完整 URL 同时用于 base URL 和绝对 endpoint，绝对 URI 覆盖 base URL，避免 `/mcp/mcp`。

## 8. Weather 工具与 Schema

| Tool | 主要参数（live tools/list 核实） | 约束 |
| --- | --- | --- |
| openmeteo_search_locations | name（必填），country/count/language | 用户明确提供的城市/区/地点；name 用地名本身 |
| openmeteo_get_forecast | latitude/longitude（必填），current_variables/hourly_variables/daily_variables、forecast_days、timezone 等 | 按实际 schema 选择变量；至少选择一类变量，控制日期与变量数量 |
| openmeteo_get_air_quality | latitude/longitude（必填），current_variables/hourly_variables、forecast_days 或日期范围、timezone 等 | CAMS 模式网格数据，不是本地站点实测 |

天气 Schema 完全来自 MCP discovery，不在 Java 复制。搜索位置后再查询天气和空气质量；综合考虑温度、体感、降水概率、雨、湿度、露点、风/阵风、UV、AQI/PM2.5，具体字段由实际 Schema 决定。
上游大结果可能截断或转为 DataCanvas 引用，而本项目不开放 dataframe 工具；Prompt 要求缩小查询，不能凭预览编造缺失时段。

## 9. 单一 McpToolFilter 与 fail closed

同一个 Bean 组合所有规则，同时匹配 Starter 配置的 connection title 与 initialize 的 serverInfo.name：

| Connection / Server name | Allow list |
| --- | --- |
| running / running-mcp-server | getRunningSummary、getRecentRuns、getPersonalBest；仅mcp模式 |
| weather / open-meteo-mcp-server | openmeteo_search_locations、openmeteo_get_forecast、openmeteo_get_air_quality |
| 未知连接、未知身份、无初始化结果或其他工具 | 全部拒绝 |

服务名只是过滤依据，不是认证。部署地址仍由操作者显式配置，不能把自报身份当作信任证明。
Discover Broadly, Expose Minimally。10个Server各20个工具会产生200套Schema，增加上下文成本、误选与攻击面；不能把发现结果全部交给模型。过滤发生在 Callback 生成前，不在调用后补救。
官方 PrefixGenerator 处理 MCP 间重名；当前两组白名单互不重名。本地和远端工具冲突则由 provider 模式隔离解决。

## 10. Agent Loop、Memory 与 RAG

ToolCallingAdvisor 接收的仍是 ToolCallback；它不需要区分 Java @Tool 与远端 MCP。模型决定下一步，无需重新设计 Agent Loop。
真实多步验收：getRunningSummary → getRunningSummary → getRecentRuns → 最终回答。
Memory 仍仅用于 `/api/agent`，用于理解“那段时间”“那晚上7点”。Memory 不是权威跑步数据库，也不是 Weather Cache；当前答案需要的数据仍重新调用 MCP。
`/api/knowledge/ask` 和 local-only `/api/knowledge/search` 保持独立，四本书仍通过原有 Tika/TokenTextSplitter/Ollama/SimpleVectorStore/QuestionAnswerAdvisor。RAG 不变成 MCP 或 Agent Tool，没有 Agentic RAG。

## 11. Trace、ToolContext 与 metadata

`TracingToolCallback` 原样委托 definition / metadata；从本地 ToolContext 提取 AgentExecutionTrace，执行前 nextToolCall，因此失败也计数。只记录：

```text
agentExecutionId=<uuid> step=1 tool=getRunningSummary source=mcp server=running status=SUCCESS latencyMs=82
```

在委托时主动传空 ToolContext，并给官方 Provider 显式设置 `ToolContextToMcpMetaConverter.noOp()`。
协议测试捕获实际 `CallToolRequest`，验证 `_meta` 为空且 arguments 只有 Schema 定义的参数。executionId、conversationId、Java Trace 对象、Memory 和秘密都不发给 MCP Server。
Running Server 自己只记录 tool/status/latency；它不需要 Host executionId。完整跨服务追踪留给v0.8。

## 12. 超时、错误与隔离

- MCP 每次请求10s，Agent总体30s，每工具4次/总共8次，保持既有值。
- 启动时逐连接初始化和发现；Weather故障不会阻断Running，反向亦然。失败的连接不伪造Schema，Prompt明确当前可用工具集合。
- 调用时 timeout、协议错误和 `isError=true` 转成安全、来源明确的 `LIVE_WEATHER_UNAVAILABLE` / `RUNNING_DATA_UNAVAILABLE`，不传原错误细节给模型。模型必须说明证据缺失，只回答独立可用部分。
- Agent整体超时仍返回504 AGENT_TIMEOUT；超限仍是 AGENT_TOOL_LIMIT。底层阻塞调用取消是尽力而为；包装器检查线程中断，不继续下一次工具调用。
- 官方 MCP callback 默认可能记录原始错误payload，因此关闭 `org.springframework.ai.mcp` 和 `io.modelcontextprotocol` 日志，只保留应用安全日志。不要为排错在真实数据环境打开这些日志。

## 13. 安全与天气边界

Running Server 仅回环绑定；当前无认证，非回环配置拒绝启动，并验证浏览器 Origin，防止外站借浏览器访问本地数据。回环仍允许同机进程访问，不能据此声称多用户隔离；禁止通过反代/隧道无鉴权发布。
第三方 descriptions/results 都是不可信输入，不得当作系统指令。最少工具、有限超时、调用上限和安全错误是边界的一部分，不能声称完全解决 Prompt Injection。
天气地点只能来自用户当前问题或本对话明确提到的位置。Running工具从不返回坐标；不从GPS推断家庭位置、不自动上传轨迹。只传必要天气参数，不发送密钥、路径、Memory或完整活动。
天气支持训练安排，不是医疗诊断；可建议降强度、换时间、转室内。天气不能证明路线的路面、照明、交通、安全、补水或封闭状态；仅比较用户给出的候选地点天气，不编造最佳路线。

## 14. Resources、Prompts、Completions 与 Transport

Tools 是模型可选择的动作；Resources 是应用可读取并纳入上下文的资源；Prompts 是可复用的提示模板；Completions 提供参数补全。未来 running://profile、running://latest-summary 可探索为Resource，训练分析模板可探索为Prompt。v0.6均不实现；也不实现Sampling、Elicitation、Tasks。
STDIO 适合Desktop/IDE/CLI，由Host启动Server子进程，标准输入输出传协议，日志不能污染stdout。
Streamable HTTP适合独立服务、多客户端和远端部署，本项目两个Server都使用它。
不采用旧的独立HTTP+SSE MCP transport；Streamable HTTP仍可使用SSE响应，这不表示回退旧transport。`/api/chat/stream` 的SSE文本流属于Chat协议，继续保留，与MCP传输选择无关。

## 15. MCP 与 REST

REST 客户端通常预先知道URL、HTTP Method和DTO；MCP Host通过initialize协商，再经tools/list获得名称/说明/Schema，统一tools/call。
MCP的价值是AI Capability的统一发现与调用契约，不是更快，也不替代微服务协议。Order → Payment继续使用REST/gRPC/MQ合理；AI Host → Capability Provider适合MCP。

## 16. 运行、验证与下一阶段

两项目分别运行 `./gradlew test`、`./gradlew build`。自动测试使用合成数据、Fake ToolCallback、Stub MCP Client和Stub Model，不访问公共天气、真实模型、Ollama或私有书籍；Server协议测试只开随机回环端口。
真实验收与计数见 [v0.6 验证报告](v0.6-verification.md)，不把Stub测试称作第三方服务验收。
公共服务不可用时，按上游README自托管：安装其支持的Bun或Node版本，clone/install后使用 `MCP_TRANSPORT_TYPE=http MCP_HTTP_PORT=3010 bun run start:http`；将WEATHER_MCP_URL改为完整localhost端点，无需修改Java。当前不复制或维护第三方Server。
生产演进需要独立评审认证、授权、速率限制、Observability和SLA；v0.6不实现Gateway、Registry、Dynamic Server Management、复杂Tool Search或OAuth Server。
下一阶段仅研究v0.7 Workflow/LangGraph的State、Node、Edge、Conditional Edge、Workflow与Agent、Deterministic Flow，不在本阶段实现。

## 官方依据

- [Spring AI MCP Client Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)
- [Spring AI MCP Server Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- [2.0.1 MCP源码包](https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-mcp/2.0.1/spring-ai-mcp-2.0.1-sources.jar)
- [2.0.1 Client自动配置源码包](https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-autoconfigure-mcp-client-common/2.0.1/spring-ai-autoconfigure-mcp-client-common-2.0.1-sources.jar)
- [Weather项目README](https://github.com/cyanheads/open-meteo-mcp-server#readme)
