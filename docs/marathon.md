# 赛事日历与官方成绩

RunAgent 在原有聊天入口提供重点全马、半马和公开领奖台查询。赛历覆盖从查询日起三个月内的已收录赛事，并展示更新时间及计划状态。成绩给出赛事届次、组别、名次、用时和官方来源。国籍、分榜或赛道纪录以来源披露为准。

## 本地启用

```bash
RUN_AGENT_MARATHON_ENABLED=true ./gradlew bootRun --args='--spring.profiles.active=local --server.address=127.0.0.1'
```

本地 `POST /api/agent` 暴露 `query_upcoming_marathons` 和 `get_marathon_podium_results`。两者使用当前的 Spring AI 工具次数限制。现有版本化 `POST /api/v1/chat` 启用后也可提问，继续使用原来的认证和访问额度。已有赛历和成绩随应用提供，直接使用种子时无需配置赛事数据目录或搜索密钥。

- 「12 月重庆有哪些半马」「重庆半马还有几天」「下个月有什么重点马拉松」查询赛历。
- 「接下来国内有哪些重量级马拉松赛事」同时按赛历的国家或地区字段和重点赛事标签筛选。「国内外」保留国内与国际赛事。
- 「重庆半马上届谁拿了前三」「2026 柏林马拉松女子组成绩」查询已核验领奖台。
- 「阿塞法最近有哪些领奖台成绩」在网站聊天中查询本地两年成绩覆盖范围。
- 「我 12 月 27 日跑重庆半马，怎么安排赛前两周训练」可结合当前问答可用的书籍资料。查询赛事天气时按已确认的开跑时段查预报。

## 同步和配置

`scripts/sync_marathon_events.py` 同步公开赛历及已核验的配置条目，可在本机或服务器定时运行。保留上一份有效快照直到新文件通过校验并完成原子替换。脚本用法见 `python3 scripts/sync_marathon_events.py --help`。

```bash
python3 scripts/sync_marathon_events.py --output .run-agent/marathon_events.json --dry-run
python3 scripts/sync_marathon_events.py --output .run-agent/marathon_events.json
```

移除 `--dry-run` 执行后，将服务的 `RUN_AGENT_MARATHON_SNAPSHOT` 指向同一文件。自定义系列和来源时使用 `--config`。定时任务应从固定的仓库目录执行，例如每天调用第二条命令并将标准错误交给任务日志。

| 配置 | 用途 |
| --- | --- |
| `RUN_AGENT_MARATHON_ENABLED` | 功能开关，默认 `false`。 |
| `RUN_AGENT_MARATHON_SNAPSHOT` | 可选的赛历快照路径，未指定时读随应用发布的种子。 |
| `RUN_AGENT_MARATHON_PODIUM_DIR` | 每届每项目的 JSON 缓存目录，默认 `.run-agent/marathon-podiums`。 |
| `RUN_AGENT_MARATHON_LOOKUP_ENABLED` | 已认证站主的在线成绩查证开关，默认 `false`。 |
| `RUN_AGENT_MARATHON_TAVILY_API_KEY` | Tavily 服务端密钥。线上回填启用但密钥为空时，缓存查询仍可用。 |

已核验结果按项目和组别缓存。访客和旧版 Agent 查询本地成绩。站主通过原有 `/api/v1/chat` 鉴权后可在缺少请求组别时尝试回填，也可明确要求刷新某届成绩。Tavily 负责发现官方来源。回填只发布能在获准官方页面核对的具体排名，失败后保留已有证据。明确的官方撤榜随来源留档。成绩查证有独立请求预算、并发上限和每日 12 次外部查证上限。

部署脚本把发布根下的 `marathon-data` 目录以只读方式挂载到容器。同步输出文件为 `marathon-data/marathon_events.json`，需能被容器 UID 10001 读取。成绩保存在独立的 `run-marathon-podiums` 卷，镜像回滚时保留。调整功能开关、搜索密钥时使用服务端环境文件并重建服务。

## 验证

```bash
./gradlew test
./gradlew build
python3 -m unittest discover -s scripts
```

自动测试使用固定时钟和替身网络或模型。用北京时间 2026 年 11 月 21 日核对重庆半马倒计时为 36 天。另查 2025 重庆半马和 2026 柏林马拉松，对照公开战报。

如果在已配置的环境做真实回填验收，选一个未缓存的历史成绩，通过站主查询验证首次查证、立即重复读取及服务重启后的缓存读取。用访客请求同一已核验成绩，并在日志里核对状态与耗时。

[完整设计与验收场景](superpowers/specs/2026-11-21-marathon-race-intelligence-design.md)
