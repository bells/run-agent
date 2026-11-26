# 微信公众号跑步内容流水线

Python 负责赛事来源发现、事实清单、图表和预览。RunAgent 网站助手直接读破三快照并返回数字和出处。公众号稿件用同一份快照，推送到草稿箱后由运营人在微信后台审阅和手动群发。

## 本地预览

使用 Python 3.10 或更高版本，在项目根目录执行。

```bash
python -m pip install -r scripts/requirements-content.txt
python scripts/run_content_pipeline.py --type race_news_athlete --athlete 'Tigst Assefa' --dry-run
python scripts/run_content_pipeline.py --type data_tutorial --input /path/to/run.fit --dry-run
python scripts/run_content_pipeline.py --type event_preview --days 21 --region 中国 --dry-run
python scripts/run_content_pipeline.py --type nutrition_guide --input /path/to/reviewed-notes.json --dry-run
python scripts/run_content_pipeline.py --type marathon_sub3 --dry-run
```

`output/drafts/` 中每个版本包含 `facts.json` 和 `preview.html`。用浏览器打开预览；存在整公里的训练稿与有已核验成绩的破三稿会附带 PNG。干运行无需微信凭据和模型调用。

| 稿件 | 输入与口径 |
| --- | --- |
| 运动员战报 | 按 `--athlete` 或 `--event-id` 读取 RunAgent 已核验的领奖台。可用 `--podiums` 指定另一个同格式事实快照。均配由项目距离和用时计算。 |
| 数据教学 | FIT、GPX 或自定义 JSON。JSON 的 `intervals` 各行提供 `duration_s`、`distance_m` 及可选 `heart_rate`。图表及 Facts 不写入坐标和绝对训练时间。合成样例可加 `--synthetic` 做预览。 |
| 赛事预告 | 输入已有赛历，可传 `--calendar`。`--days` 为 14 至 28 天，支持按地区和赛历记录的 `--level` 筛选。 |
| 营养补给 | 输入人工核对过的 JSON。包含 `subject`、`season`、`phase` 和 `claims` 数组，每条提供 `text` 及 `source`（`url`、`organization`）。 |
| 年度破三统计 | 输入 Python 采集器发布的汇总。榜单只使用同口径核验后的净计时，口径待核实的披露附原文。 |

草稿正文由确定性模板渲染。赛事来源应先校验再放入快照。赛事故事、采访和营养说明未来可以作为独立语料检索，结构化统计继续用现有数据查询。有关年度目录、比赛成绩和读写路径，见 [赛事日历和成绩](marathon.md)。

## 发现近期来源

```bash
python scripts/discover_marathon_evidence.py '南京马拉松' --domain sports.nanjing.gov.cn --hours 72
```

命令使用 `RUN_AGENT_MARATHON_TAVILY_API_KEY`，按 Tavily 返回的发布日期筛选，只输出待核验候选链接。它不把搜索摘要写进事实清单。确认赛事、项目、届次及口径后，将对应来源登记进破三采集清单或官方结果快照。

## 推送草稿

在进程环境中设置 `WECHAT_APP_ID` 与 `WECHAT_APP_SECRET`，然后把 `--dry-run` 换成 `--push-draft`。训练稿或严格破三榜可用生成图作为封面，其他稿件需传 `--cover` 并使用有权发布的本地 PNG 或 JPEG。

```bash
python scripts/run_content_pipeline.py --type race_news_athlete --event-id berlin_marathon_2026 \
  --cover assets/covers/race.jpg --push-draft
```

客户端缓存 access token，上传正文图和永久封面并调用 `draft/add`，返回 `media_id`。相同稿件及素材会复用已记下的回执。提交时若连接中断且结果不明，先在微信后台核对草稿箱。确认没有生成草稿后才移除错误提示给出的 `.run-agent/wechat/` 下待确认回执，再重试。实际微信账号接口需要单独验收。

## 回归检查

```bash
python -m unittest discover -s scripts
./gradlew build
```
