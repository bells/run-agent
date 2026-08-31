# RunAgent

Personal Running AI Agent built with Java and Spring AI.

RunAgent 是一个个人 AI Agent 学习与实战项目，未来会为
[Running Web](https://run.watsonzhu.cn) 提供自然语言分析能力。当前版本是 **v0.1**，只保留最短学习链路：

```text
User → Spring Boot → ChatClient → LLM → Response
Natural Language → LLM → Structured Output → Java Object
```

## v0.1 能力

- 普通 Chat：`POST /api/chat`
- SSE Streaming Chat：`GET /api/chat/stream`
- Structured Output：`POST /api/intent`

当前没有接入真实跑步数据。系统提示词会明确禁止模型捏造用户的跑步历史。

## 技术栈

- Java 21
- Gradle 9.7.1（通过 Gradle Wrapper）
- Spring Boot 4.1.1
- Spring AI 2.0.1
- Spring WebFlux / Reactor
- Bean Validation
- JUnit 5

项目使用 Spring AI 2.0.1 BOM 管理 AI 组件版本。OpenAI Starter 负责创建 `ChatClient.Builder`，
业务层统一构建和调用 `ChatClient`。

## 环境要求

- JDK 21
- 可访问所配置 LLM 服务的网络
- OpenAI API Key，或兼容 OpenAI API 的服务

无需单独安装 Gradle。

## 配置

推荐通过环境变量配置，不要把真实密钥写入仓库：

```bash
export OPENAI_API_KEY="your-api-key"
export OPENAI_MODEL="gpt-4.1-mini"
```

使用 OpenAI-compatible provider 时还可以覆盖：

```bash
export OPENAI_BASE_URL="https://your-provider.example.com"
```

仓库提供了 `application-local.yml.example`。如需本地配置文件，可复制为
`src/main/resources/application-local.yml`，该文件已加入 `.gitignore`，然后使用
`--spring.profiles.active=local` 启动。示例文件也只引用环境变量，不包含密钥。

## 启动与构建

```bash
./gradlew bootRun
```

运行测试和完整构建：

```bash
./gradlew test
./gradlew build
```

## API 示例

### 普通 Chat

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"What is a good easy run?"}'
```

响应：

```json
{"content":"..."}
```

### Streaming Chat

```bash
curl -N 'http://localhost:8080/api/chat/stream?message=hello'
```

接口使用 `text/event-stream; charset=UTF-8`。选择 GET 是为了让浏览器未来可以直接用原生
`EventSource` 接入；查询参数限制为 4000 字符。每个片段是 `token` 事件。客户端断开时 Reactor
会传播 cancel 信号，底层流随之取消。流已经开始后发生的错误会以 `error` SSE 事件返回，而不是
暴露服务端异常。

### Structured Output

```bash
curl -X POST http://localhost:8080/api/intent \
  -H 'Content-Type: application/json' \
  -d '{"message":"帮我分析最近一个月的跑步情况"}'
```

响应示例：

```json
{
  "intent": "RUNNING_ANALYSIS",
  "startDate": "2026-08-01",
  "endDate": "2026-08-31",
  "originalQuestion": "帮我分析最近一个月的跑步情况"
}
```

实现使用 `ChatClient.call().entity(RunningIntent.class)`。Spring AI 根据 Java record 生成结构化
Schema，并把模型结果映射回强类型对象，不是只在提示词中要求模型“返回 JSON”。服务层还会校验
日期顺序，并用原始请求覆盖 `originalQuestion`。

## 代码结构

```text
src/main/java/cn/watsonzhu/runagent
├── RunAgentApplication.java
├── agent/RunAgentService.java
├── controller/
│   ├── ChatController.java
│   └── IntentController.java
├── exception/
├── model/
└── prompt/PromptCatalog.java

src/main/resources
├── application.yml
└── prompts/
    ├── run-agent-system.txt
    └── running-intent-system.txt
```

Controller 只负责 HTTP、校验与响应适配；AI 调用集中在 `RunAgentService`。普通调用是阻塞式
`ChatClient.call()`，WebFlux Controller 将它切到 `boundedElastic`，避免阻塞 Netty 事件循环；
Streaming 直接保留 Reactor `Flux` 链路。

## Roadmap

```text
v0.2 Tool Calling
v0.3 Agent Loop
v0.4 Memory
v0.5 RAG
v0.6 MCP
v0.7 Evaluation / Observability
v0.8 Workflow / Multi-Agent
```

v0.1 明确不包含 Tool、Memory、数据库、Embedding、RAG、MCP、Workflow 或 Multi-Agent。
下一阶段加入 Tool Calling 时，入口应保持在 `RunAgentService` 的 `ChatClient` 配置和调用链，
Controller API 不需要承担工具编排逻辑。
