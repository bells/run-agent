# RunAgent 私有部署

Mac 构建 Git 提交对应的 Java 21 JAR。Linux 主机基于固定的 Temurin 镜像组装容器。RunAgent 的 HTTP 端口只发布到宿主机的 `127.0.0.1:18080`。服务器需要 Docker Engine、支持 `--wait` 的 Compose、Bash 和 `flock`。发布账号通过 Docker 权限或已有的非交互 sudo 操作容器。

## 首次准备

核对 SSH 主机指纹。可用可信的 SSH 别名，或通过 `RUN_AGENT_SSH_KNOWN_HOSTS` 指向经核验的主机指纹文件。在服务器由部署账号创建权限为 0700 的 `~/.local/share/run-agent`，并在其中用 0600 权限保存 `provider.env`。

```dotenv
AI_GATEWAY_BASE_URL=https://api.deepseek.com
AI_GATEWAY_MODEL=deepseek-flash
AI_GATEWAY_API_KEY=<your-provider-key>
```

可选的 `private-chat.env` 同样使用 0600 权限。设置 `RUN_AGENT_PRIVATE_CHAT_ENABLED=true`，并将服务器生成的 48 位十六进制令牌放到 `RUN_AGENT_PRIVATE_CHAT_TOKEN`。可用 `openssl rand -hex 24` 生成令牌。公网代理仅转发 `POST /api/v1/chat`，示例见 [Caddyfile.example](Caddyfile.example)。令牌由使用者在前端录入，模型密钥只留在服务器。

## 发布与回滚

在 RunAgent 工作树用 Java 21 执行。以下命令中的 `run-agent-hk` 为 SSH 目标。

```bash
./deploy/deploy.sh check run-agent-hk
./deploy/deploy.sh deploy run-agent-hk main
./deploy/deploy.sh rollback run-agent-hk
```

脚本将已通过 Gradle 构建的 JAR 和运行配置经 SSH 上传。新版本健康后才切换发布状态。启动失败会恢复上一次健康版本，SSH 中断会在下一次发布操作时检查并恢复。单服务更新和回滚会有短暂的服务中断。模型连通性须用独立的真实问答验证。

```bash
ssh -N -L 127.0.0.1:18081:127.0.0.1:18080 run-agent-hk
```

随即在本机访问 `http://127.0.0.1:18081/api/chat`。`provider.env` 和 `private-chat.env` 的更新在下次容器重建时生效。服务端保存当前与上一次健康发布的引用，可审计的镜像版本保存在 Docker 中。按磁盘余量管理历史镜像。

## 活动数据

使用生成后的 JSON。发布个人活动数据前，确认服务器和模型的数据使用授权。

```bash
./deploy/deploy.sh sync-data run-agent-hk /path/to/activities.json
```

导出器保留 Run 记录的活动 ID、类型、距离、时长和本地日期字段。SSH 传输完整后原子替换数据文件，容器从只读目录读数据。更新数据无需重启。
