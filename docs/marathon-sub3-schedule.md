# 生产破三数据定时补采

部署账号的 systemd 用户定时器在每天北京时间 03:00 运行采集器。服务器离线错过一次或多次触发时，定时器恢复后补跑一次。首次安装需要由部署账号显式启用。

任务读取配置的年度来源，复用采集器的输出锁与原子写入。候选快照通过格式和 2 MiB 大小检查后发布到 `marathon-data/marathon-sub3.json`，上一份快照先保存到同目录的 `.marathon-sub3.previous.json`。采集器最多运行 8 分钟，systemd 将整个启动任务限制为 9 分钟。采集命令失败、超时、输出无效或发现正在写入时，当前快照保留。单届证据缺项由采集器记录为待核验或不可用，具备有效旧证据时按采集器规则保留。RunAgent 从同一个只读挂载读取新快照，无需重启。

## 安装与验收

在 Linux 宿主机使用已有 RunAgent 部署账号。将包含此任务的已审阅 Git 版本放入独立目录 `~/.local/share/run-agent/marathon-sub3-job`。容器发布包只包含应用构件，宿主机任务从这个目录读取 Python 源码。需要 Python 3.10+、时区数据库及 systemd 用户服务。使用官方目录 PDF 时，在虚拟环境安装项目锁定的 `pdfplumber`。

```bash
job="$HOME/.local/share/run-agent/marathon-sub3-job"
python3 -m venv "$job/.venv"
# 使用 --catalog-pdf 时安装此项。
"$job/.venv/bin/python" -m pip install "$(sed -n '/^pdfplumber==/p' "$job/scripts/requirements-content.txt")"

install -d -m 700 "$HOME/.config/run-agent" "$HOME/.config/systemd/user"
install -m 600 "$job/deploy/marathon-sub3.env.example" "$HOME/.config/run-agent/marathon-sub3.env"
install -m 644 "$job/deploy/marathon-sub3-refresh.service" "$job/deploy/marathon-sub3-refresh.timer" \
  "$HOME/.config/systemd/user/"
```

将已核对的 `manifest`、可选 PDF、公告归档和成绩 CSV 放在 `marathon-data` 挂载之外。修改 `~/.config/run-agent/marathon-sub3.env` 中的绝对路径。成绩 CSV 的相对路径以年度清单所在目录为基准。

| 配置项 | 用途 |
| --- | --- |
| `RUN_AGENT_MARATHON_SUB3_MANIFEST` | 必填。已审阅的年度候选和证据来源。 |
| `RUN_AGENT_MARATHON_SUB3_CATALOG_PDF` | 可选。同年首次导入官方 PDF 后，后续刷新也应提供该 PDF。 |
| `RUN_AGENT_MARATHON_SUB3_ANNOUNCEMENT_DIR` | 可选。读取目录里已审阅的 UTF-8 `<event_id>.html`。同届使用不同的披露和严格口径公告时，分别保存为 `<event_id>.disclosure.html` 和 `<event_id>.strict.html`。省略时，从年度清单已登记的 HTTPS 公告取证。 |
| `RUN_AGENT_DEPLOY_DIR` | 可选。默认 `~/.local/share/run-agent`。使用自定义部署根目录时，设为与应用发布一致的绝对路径。其下的 `marathon-data` 应已由应用发布创建，并归此部署账号所有。 |

如需在登出和主机重启后继续运行，由具备主机管理权限的账号为部署账号启用 linger。先用配置好的输入执行一次只读检查，再手动发布并查看日志。首次发布后，可在已有聊天入口查询同年破三人数，核对覆盖范围和来源。

```bash
loginctl enable-linger "$USER"
systemctl --user daemon-reload
systemd-analyze --user verify "$HOME/.config/systemd/user/marathon-sub3-refresh.service" \
  "$HOME/.config/systemd/user/marathon-sub3-refresh.timer"

systemd-run --user --wait --pipe --collect \
  --property="EnvironmentFile=$HOME/.config/run-agent/marathon-sub3.env" \
  "$job/.venv/bin/python" "$job/deploy/marathon-sub3-refresh.py" --dry-run
systemctl --user start marathon-sub3-refresh.service
journalctl --user -u marathon-sub3-refresh.service -n 30 --no-pager

systemctl --user enable --now marathon-sub3-refresh.timer
systemctl --user list-timers --all marathon-sub3-refresh.timer
```

首次自动触发后，再用该服务的日志核对运行时间和发布结果。定时任务使用当前年度清单已登记的来源。新届次或新证据经核对后加入清单，下一次运行会重新采集。切换统计年份前，单独归档仍需保留的年度快照。

## 停用与恢复上一份快照

停用定时器后，等待正在运行的服务结束，或停止该次运行。以下恢复命令与刷新共用锁，原子替换当前文件并保留备份文件。只发布过一份快照时，恢复命令会提示暂无备份。

```bash
systemctl --user disable --now marathon-sub3-refresh.timer
systemctl --user stop marathon-sub3-refresh.service
systemd-run --user --wait --pipe --collect \
  --property="EnvironmentFile=$HOME/.config/run-agent/marathon-sub3.env" \
  "$job/.venv/bin/python" "$job/deploy/marathon-sub3-refresh.py" --rollback
```

仓库内的离线测试可在 macOS 或 Linux 执行。

```bash
python3 deploy/test/marathon-sub3-refresh-test.py
```

如需以官方网页归档补救个别站点的网络失败，使用 `RUN_AGENT_MARATHON_SUB3_ANNOUNCEMENT_FALLBACK_DIR`。它与纯离线的 `RUN_AGENT_MARATHON_SUB3_ANNOUNCEMENT_DIR` 互斥。
