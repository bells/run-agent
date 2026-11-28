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
| `RUN_AGENT_MARATHON_SCHEDULE_ENABLED` | 赛后成绩定时补采开关，默认 `false`。需要同时启用赛事和在线查证。 |
| `RUN_AGENT_MARATHON_TAVILY_API_KEY` | Tavily 服务端密钥。留空时仍可查缓存、直读已知完赛页和已登记的图片公示。 |

已核验结果按项目和组别缓存。访客和旧版 Agent 查询本地成绩。站主通过原有 `/api/v1/chat` 鉴权后可在缺少请求组别时尝试回填，也可明确要求刷新某届成绩。回填优先读取赛历已记录的完赛来源，榜单已核验且完整时直接入库，其余情况用 Tavily 发现官方来源。回填只发布能在获准官方页面核对的具体排名，失败后保留已有证据。明确的官方撤榜随来源留档。成绩查证有独立请求预算、并发上限和每日 12 次外部查证上限。

定时补采每天北京时间 02:00 从已加载的赛历选择项目。已确认或已完赛、且赛事当地日期已过比赛日 1–7 天的项目进入候选。每次最多检查 6 个未补齐项目，与站主手动查证共用每日 12 次联网预算。已缓存完整男女总榜的项目跳过。登记了图片公示的项目还会检查中国籍榜是否补齐。已有核验结果继续可查。

## 赛事动态联网查询

在已有的 `/api/v1/chat` 中，访客和站主可查询「今年上马报名多少人」「上海马拉松有哪些名将要参加」「张水华明天参加哪场马拉松」。首次部署在服务器环境文件中设置 `RUN_AGENT_MARATHON_UPDATES_ENABLED=true`，同时启用赛事模块并配置已有的 Tavily 密钥。访客仍需启用公开问答。

| 配置 | 默认 |
| --- | --- |
| `RUN_AGENT_MARATHON_UPDATES_ENABLED` | `false` |
| `RUN_AGENT_MARATHON_UPDATES_VISITOR_DAILY_LIMIT` | 所有访客共 12 次联网查询 |
| `RUN_AGENT_MARATHON_UPDATES_OWNER_DAILY_LIMIT` | 站主 24 次联网查询 |
| `RUN_AGENT_MARATHON_UPDATES_DISABLE_THINKING` | `false`。DeepSeek 网关可显式设为 `true`，仅在赛事动态结构化抽取时关闭推理，保留 JSON 输出预算。 |

额度按北京时间每日重置，与赛后成绩查证额度独立。服务先查内存缓存，查到资料保留 30 分钟，暂未找到的结果保留 5 分钟。缓存命中不占用联网额度，服务重启清空缓存和计数。动态查询使用一次有界搜索和一次结构化提取，搜索词来自赛事或公开跑者姓名、日期和查询类别。

回答区分报名人数、名额和实际参赛人数，精英名单区分确认、受邀和报道。只有可在搜索来源的摘录中定位的事实才会展示，每条附来源链接和可得的发布日期。上线验收用访客身份执行三类示例，再复查缓存命中与缺少证据的返回。日志 `marathon_updates` 记录类别、状态和耗时。

[设计与验收范围](superpowers/specs/2026-11-28-marathon-updates-design.md)

## 图片成绩公示

`src/main/resources/marathon/marathon_image_notices.json` 按赛事届次、比赛日和项目登记公示页、附件、SHA-256 和榜单区域。首个来源是 2026 南京马拉松组委会。公示换图或添加其他届次时，核对版本和裁切区域后更新登记。

优先使用已核验的网页正文或表格。缺少组别或前三名时，服务用公示页作为 `Referer` 下载该页列出的已登记 PNG。校验原图 SHA-256 后，用 Pillow 裁切缩放、用 Tesseract 识别登记区域。表头须含排名、姓名和枪声成绩，前三行须同时通过名次、姓名和用时校验。图片成绩的缓存保留公示页和原图地址。OCR 失败时保留已有网页证据。

发布镜像包含 Python 3、Pillow、Tesseract 及其中英文语言模型。本机查证图片成绩时，需要在 `PATH` 上提供带 Pillow 的 Python 3 和带 `chi_sim`、`eng` 语言模型的 Tesseract。已过定时窗口的历史公示可由站主请求刷新对应届次。

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

## 年度全马破三统计

中国大陆田协 A/B 类全马按有效净计时严格小于 `03:00:00` 计算。度量以赛事届次为单位。官方仅写「破三」的数字保留出处并标为口径待核实。赛历名单提供候选，是否已完赛以之后的官方证据更新。

先在独立 Python 环境安装 PDF 导入和 PNG 预览依赖。常规采集和无图草稿使用 Python 标准库。以下命令从 RunAgent 仓库根目录运行。

```bash
python3 -m pip install -r scripts/requirements-content.txt
python3 scripts/collect_marathon_sub3.py --catalog-pdf .run-agent/caa-2026.pdf
python3 scripts/render_marathon_sub3_draft.py --dry-run
```

- [官方 2026 年初赛事目录 PDF](https://file.shuzixindong.com/changzheng/84554/fddbe29f6e434035918201d2a17dbcac.pdf) 共 492 条。导入固定版 PDF 时按序号及摘要校验，名单变更后更新审核记录或将新届次加入清单。原始 PDF 的计划日期可为某月或日期范围。
- `scripts/marathon_sub3_sources.json` 登记已核对并可以采集的届次。选项 `--manifest` 可指定自己的清单。起始清单包含两个官方公告。以同一段原文明确写出全马、有效净计时、严格小于三小时和总数的公告可登记为 `strict_announcement`。正则需要同时捕获 `basis` 与 `count`，并登记已人工核对的 UTF-8 正文 `sha256`。公告变动须重新核对。带 PDF 时会另外跟踪目录内其余全马候选。更新已经导入完整 PDF 的同年快照时需继续提供 PDF。缺少结果来源时记录为待核验。
- 仅对登记为已完赛的届次采集结果。默认直接获取官方公告，遇到站点无法自动访问时，可通过 `--announcement-dir` 指定已经核对的本地原文，采用 UTF-8，仅有一个公告地址时按 `<event_id>.html` 命名。有不同的严格榜与披露地址时，分别用 `<event_id>.strict.html` 和 `<event_id>.disclosure.html`。该目录应由操作人员控制。命令每次只访问登记过的来源，读取或解析失败会打印届次和错误类型。
- 完整官方净计时导出登记在届次的 `results` 字段。CSV 表头为 `bib,net_time,status`。成绩格式为 `H:MM:SS` 或 `HH:MM:SS`，有效成绩状态为 `FINISHED`，`DNS`、`DNF`、`DSQ`、`DQ`、`INVALID` 排除。清单登记 `file`（相对清单目录）或 `download_url`（直接公开 CSV 地址）、`url`（官方出处）、`timing_basis: NET_TIME`、`coverage: ALL_RESULT_ROWS`、`expected_rows` 和 `sha256`。不同计时商的 CSV 可以通过 `columns` 显式映射 `bib`、`net_time` 和 `status`，通过 `statuses` 映射原值到上述标准状态。默认 UTF-8，也可显式配置 `encoding: gb18030`。只给有完整性证明的公开成绩登记该来源，原始下载和逐人 CSV 保存在本地。
- 采集命令的 `--dry-run` 将候选 JSON 打到标准输出。`--output` 指定持久化文件，默认 `.run-agent/marathon-sub3.json`。可由服务器任务每天运行生产采集，输出到部署根目录下的 `marathon-data/marathon-sub3.json`。Java 容器已有只读挂载，配置 `RUN_AGENT_MARATHON_SUB_THREE_SNAPSHOT` 指向对应文件。尚未验证数据时，查询显示待核验的范围。
- 草稿预览默认输出到 `output/drafts/`，同一版本可重跑，包含 `facts.json` 和 `preview.html`。存在可排名成绩时一并生成 `ranking.png`。浏览器直接打开 HTML 即可。

网站聊天示例「2026 年中国马拉松破三人数前十位的马拉松」和「2026 年锡马有多少人破三？」。旧版 Agent 可调用 `get_marathon_sub_three_counts` 获取结构化结果。

[设计及后续公众号阶段](superpowers/specs/2026-11-26-marathon-sub3-design.md)

公众号选题和本地预览的统一入口见 [微信公众号跑步内容流水线](content-pipeline.md)。

[生产定时补采与回滚](marathon-sub3-schedule.md) · [赛事公开资料检索](marathon-evidence.md)
