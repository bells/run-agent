# RunAgent 私有部署

Mac 构建 Git 提交对应的 Java 21 JAR。Linux 主机基于固定的 Temurin 镜像组装容器。RunAgent 的 HTTP 端口只发布到宿主机的 `127.0.0.1:18080`。服务器需要 Docker Engine、支持 `--wait` 的 Compose、Bash 和 `flock`。发布账号通过 Docker 权限或已有的非交互 sudo 操作容器。

## 首次准备

核对 SSH 主机指纹。可用可信的 SSH 别名，或通过 `RUN_AGENT_SSH_KNOWN_HOSTS` 指向经核验的主机指纹文件。在服务器由部署账号创建权限为 0700 的 `~/.local/share/run-agent`，并在其中用 0600 权限保存 `provider.env`。

```dotenv
AI_GATEWAY_BASE_URL=https://api.deepseek.com
AI_GATEWAY_MODEL=deepseek-flash
AI_GATEWAY_API_KEY=<your-provider-key>
```

同机私有 MCP 部署需要 `private-chat.env`，权限为 0600。设置 `RUN_AGENT_PRIVATE_CHAT_ENABLED=true`，并将服务器生成的 48 位十六进制令牌放到 `RUN_AGENT_PRIVATE_CHAT_TOKEN`。可用 `openssl rand -hex 24` 生成令牌。公网代理的默认为 `POST /api/v1/chat` 和 `POST /api/v1/training/stats`，单次复盘配置见本页后文，示例见 [Caddyfile.example](Caddyfile.example)。两条路由使用同一私有令牌，模型密钥只留在服务器。训练统计在同机 MCP 部署中启用，统计查询由服务端完成。

## 发布与回滚

在 RunAgent 工作树用 Java 21 执行。以下命令中的 `run-agent-hk` 为 SSH 目标。

```bash
./deploy/deploy.sh check run-agent-hk
./deploy/deploy.sh deploy run-agent-hk main
./deploy/deploy.sh rollback run-agent-hk
```

脚本从指定提交读取发布脚本、Dockerfile 和 Compose 配置，并构建 JAR 后经 SSH 上传。每次发布目录和镜像标签都记录完整的源提交 SHA。新版本健康后才切换发布状态。启动失败会恢复上一次健康版本，SSH 中断会在下一次发布操作时检查并恢复。单服务更新和回滚会有短暂的服务中断。模型连通性须用独立的真实问答验证。

```bash
ssh -N -L 127.0.0.1:18081:127.0.0.1:18080 run-agent-hk
```

可在本机通过隧道连通性测试 `POST http://127.0.0.1:18081/api/v1/chat`。同机私有部署关闭旧版模型路由，通用问答和训练统计分别使用两条版本化私有路由。`provider.env` 和 `private-chat.env` 的更新在下次容器重建时生效。服务端保存当前与上一次健康发布的引用，可审计的镜像版本保存在 Docker 中。按磁盘余量管理历史镜像。

## 活动数据

输入使用已生成的 Watson Running 活动 JSON。先用合成数据验证，再将已获授权的个人快照同步至服务器。训练统计查询只在服务器端处理。

```bash
./deploy/deploy.sh sync-data run-agent-hk /path/to/activities.json
./deploy/deploy.sh rollback-data run-agent-hk
```

导出器检查日期、移动时长、距离和活动 ID，输出 Run 记录的内部 ID、活动类型、距离、移动时长和本地日期。日期以当天零点写入，兼容旧版本的时间戳格式。内部 ID 由本机密钥派生，密钥默认首次生成到 `~/.local/state/run-agent/export-key`，权限为 0600。需要迁移到另一台发布机器时，可用 `RUN_AGENT_EXPORT_KEY_FILE` 指向同一密钥以保持 ID 稳定。

服务端校验 8 MiB 的快照大小上限和 SHA-256 后原子替换。容器从只读目录读取，更新无需重启。重复同步相同快照保留原回退点。SSH 中断后，下一次快照操作先完成已登记的原子替换。回退恢复上一份快照，首次导入的回退会恢复为未配置数据。私有快照存入私有 MCP 发布专用的 `private-data` 目录。旧版和单服务发布继续挂载原有的 `data` 目录，软件回退可与私有快照回退独立执行。

## 同机 Running MCP Server

```bash
./deploy/deploy.sh deploy-mcp run-agent-hk /path/to/running-mcp-server <run-agent-sha> <mcp-sha>
```

此模式从指定 Running MCP Server 提交构建第二个镜像。两仓均默认使用各自的 `HEAD`。两个进程使用 Linux 主机网络并绑定回环。MCP 在 `127.0.0.1:8090/mcp` 提供工具，独自挂载数据目录。RunAgent API 仍使用 `127.0.0.1:18080`。RunAgent 的启动健康检查需要发现三个 Running MCP 工具。更新 MCP 版本时用同一命令重新发布，以便 RunAgent 在启动时重新发现工具。回滚会按上一次健康发布的配置恢复单服务或双服务。

## 生产探测

`bash deploy/smoke-public.sh` 检查公开首页、汇总页和两条 API 的匿名拒绝。GitHub Actions 定时运行探测。部署后另用受控令牌和合成快照确认统计结果，并验证通用问答。

## 单次跑步复盘

复盘读取和生成使用同一条版本化路径，按 HTTP 方法区分。部署前先用合成发布摘要和替身模型验证分段计算、生成去重和回看。在启用真实训练指标的模型调用前，应确认对应字段的用途与授权，再更新服务端配置。

同机 Compose 为报告准备独立的 `run-reviews` 命名卷，挂载到 `/var/lib/run-agent/reviews`。卷由服务 UID 10001 写入，MCP 活动快照继续使用自己的只读目录。回滚到没有复盘接口的版本时保留卷，恢复支持的版本即可读回。

| 配置 | 用途 |
| --- | --- |
| `RUN_AGENT_RUN_REVIEW_ENABLED` | 默认 `false`。启用复盘 API 和已有报告读取。 |
| `RUN_AGENT_RUN_REVIEW_GENERATION_ENABLED` | 默认 `false`。开启后才允许生成新报告。 |
| `RUN_AGENT_RUN_REVIEW_EVIDENCE_BASE_URL` | 开启生成时配置 Watson Running 的固定 HTTPS 发布地址。 |
| `RUN_AGENT_RUN_REVIEW_TIMEOUT` | 生成期限，默认 25 秒。应短于客户端和代理超时。 |
| `VITE_RUN_AGENT_RUN_REVIEW` | 前端构建开关，默认关闭。 |

代理按 [Caddyfile 示例](Caddyfile.example) 转发数字活动 ID 的 GET 和 POST。验证浏览器同源请求、匿名 401、跨重启回看与停用生成时仍可读已有报告。[复盘规格](../docs/superpowers/specs/2026-11-19-single-run-review-design.md)

## 公开体验、天气与在线书库

使用现有同机 MCP 发布。准备与当前 Watson Running 发布版本一致的公开活动 JSON，用 `deploy/deploy.sh sync-public-data <ssh-target> <published-activities.json>` 导出并同步。脚本只保留公开 ID、运动类型、日期、距离和移动时长，并把快照写到独立的 `public-data` 目录。使用 `rollback-public-data` 独立回退公开快照。

在服务器发布根目录配置以下项目。发布根目录默认为 `~/.local/share/run-agent`。

| 位置 | 设置 |
| --- | --- |
| `runtime.env` | `RUN_AGENT_MCP_PROFILES=mcp,weather-mcp` |
| `provider.env` | `RAG_ENABLED=true`，配置 `RAG_OLLAMA_BASE_URL` 与 `RAG_EMBEDDING_MODEL`。生成继续使用现有 DeepSeek 配置。 |
| `knowledge/` | `vector-store.json` 和 `vector-store.json.manifest`。文件包含书籍片段，限制为部署账号和容器 UID 10001 可读。生产 Compose 只读加载并校验一致性。 |
| `private-chat.env` | `RUN_AGENT_PUBLIC_API_ENABLED=true`、`RUN_AGENT_PUBLIC_REVIEW_IDS`。复盘 ID 来自当前网站发布的真实活动，和前端公开 ID 一致。可覆盖 `RUN_AGENT_PUBLIC_CHAT_DAILY_LIMIT`、`RUN_AGENT_PUBLIC_TRAINING_ADVICE_DAILY_LIMIT` 和 `RUN_AGENT_PUBLIC_REVIEW_DAILY_LIMIT`。 |
| 前端生产构建 | `VITE_RUN_AGENT_PUBLIC_API=true`、`VITE_RUN_AGENT_RUN_REVIEW=true` 和 `VITE_RUN_AGENT_PUBLIC_REVIEW_IDS`。 |

预先用与服务器相同的 Embedding 模型、端点身份和分块配置构建索引。启动前确认 Embedding 服务已加载模型，核查主机内存。应用启动后确认 RAG 输出 `LOADED`，MCP 发现日志包含 `running` 与 `weather`，再配合同源前端检查公开入口。用一条包含城市与时段的问答确认预报时段和书籍来源，随后核对公开统计和指定活动的生成及回看。个人令牌继续访问原数据范围。模型额度与报告存储按访问权限独立处理。

在 GitHub 仓库将 `RUN_AGENT_PUBLIC_API_ENABLED` Actions 变量设为 `true` 后，定时 smoke 检查匿名统计和匿名聊天参数校验。探针发送无效的聊天请求来验证路由，避免触发模型调用。
