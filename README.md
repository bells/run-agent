# RunAgent

RunAgent 是 [Watson Running](https://run.watsonzhu.cn/) 的跑步 AI 后端。站点公开展示跑步记录和统计，Classic 界面提供输入访问令牌后使用的跑步问答助手。后端运行在香港服务器，通过 DeepSeek 提供中文跑步问答。

## 线上能力

| 能力 | 当前状态 |
| --- | --- |
| 私有跑步问答 | Watson Running 通过同源 `POST /api/v1/chat` 发起请求。API 使用 Bearer 令牌鉴权，并限制并发、请求频率、超时和模型输出长度。 |
| 我的训练 | 私有统计 API 通过同机 MCP 查询指定区间的跑量、平均配速和近似 PB。查询直接返回可核对的统计。 |
| 跑步数据工具 | 同机 Running MCP Server 提供训练汇总、最近记录和个人最佳三项只读工具。已用合成数据验证工具发现、调用和重启恢复。 |
| 发布与回滚 | 前端由 GitHub `main` 触发 Vercel 发布。香港后端使用 Docker Compose，发布脚本记录当前和上一健康版本，支持启动失败恢复与手动回滚。 |

通用问答由 DeepSeek 提供。个人训练统计通过独立的私有接口读取活动快照。[合成数据演示](docs/training-demo.md)

部署验收涵盖授权问答、匿名请求拒绝、合成数据工具调用和服务重启恢复。[线上部署验收记录](docs/superpowers/plans/2026-11-18-hong-kong-deployment.md#verification)

## 架构

```text
Watson Running（Vercel）
  └─ POST /api/v1/chat → 同源转发 → Caddy → RunAgent 私有聊天 → DeepSeek

Watson Running「我的训练」及 RunAgent 内部分析入口
  └─ RunAgent MCP 客户端 → 同机 Running MCP Server → 只读活动快照
```

| 仓库 | 负责 |
| --- | --- |
| [watson-running](https://github.com/bells/watson-running) | React / TypeScript 跑步站点、地图、统计和私有问答界面 |
| [run-agent](https://github.com/bells/run-agent) | Java 21、Spring Boot、Spring AI、WebFlux。提供聊天 API、模型接入、MCP 客户端及分析工作流。 |
| [running-mcp-server](https://github.com/bells/running-mcp-server) | 通过 MCP Streamable HTTP 提供只读跑步统计，独立构建，由后端同机部署。 |

公网代理只转发版本化聊天和私有训练统计接口。后端应用端口、管理端口和 MCP 服务绑定回环。模型密钥保存在服务端。活动快照通过单独的只读挂载提供给 MCP。

## 本地运行

需要 Java 21。使用 Gradle Wrapper 构建，自动化测试使用替身模型和合成数据。

```bash
./gradlew build
```

如需实际问答，复制 [配置模板](application-local.yml.example) 到 `src/main/resources/application-local.yml`，按模板填写兼容 OpenAI Chat Completions 的服务地址、模型及密钥。也可通过环境变量配置 `AI_GATEWAY_BASE_URL`、`AI_GATEWAY_MODEL` 和 `AI_GATEWAY_API_KEY`。

```bash
./gradlew bootRun --args='--spring.profiles.active=local --server.address=127.0.0.1'
```

本地问答可以调用 `POST /api/chat`，JSON 请求体为 `{"message":"跑前热身可以怎么做？"}`。需要运行 MCP 或其他分析入口时，请参照下方文档启用对应配置。

## 部署与开发文档

- [发布、回滚与同机 MCP 运维](deploy/README.md)
- [MCP 工具发现与白名单](docs/mcp.md) · [训练分析工作流](docs/workflow.md) · [多 Agent 编排](docs/multi-agent.md)
- [离线评估](docs/evaluation.md) · [可观测性](docs/observability.md) · [成本与预算](docs/performance.md) · [故障治理](docs/reliability.md)
- [v0.11 README 归档](README.v0.11.md)，保留技术演进、各阶段配置和 API 示例。
