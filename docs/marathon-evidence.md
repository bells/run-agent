# 赛事公开资料检索

赛事资料集合收录可公开引用的战报、赛道指南和赛后采访摘要。RunAgent 从匹配届次的资料中检索段落，每条结果保留赛事、届次、资料类型、标题、发布方、发布日期和原文链接。赛历、领奖台和破三统计继续使用各自的结构化数据。

## 启用和更新

`scripts/marathon_evidence_sources.json` 保存审核过的公开出处及主题摘要。以下命令从这一清单发布资料索引的输入，并在同版本目录下保留带原文链接的 Markdown。

```bash
python3 scripts/build_marathon_evidence.py
```

本地运行时，启用赛事功能并指定资料文件。配置项为 `run-agent.marathon.evidence-corpus`，未设置路径时关闭资料检索。Compose 从已挂载的 `/marathon-data/marathon-evidence.json` 读取语料。

```bash
RUN_AGENT_MARATHON_ENABLED=true \
RUN_AGENT_MARATHON_EVIDENCE_CORPUS=.run-agent/marathon-evidence.json \
./gradlew bootRun --args='--spring.profiles.active=local --server.address=127.0.0.1'
```

服务在启动时读取并校验整个文件，然后从内存检索。新文件先在本地验证，再替换部署目录中的文件并重启服务。文件缺失或校验失败时，当前进程不提供赛事资料。服务日志只记录通用加载告警。

## 语料契约

语料采用 UTF-8 JSON。下面使用虚构赛事展示文件形状。

```json
{
  "schemaVersion": 1,
  "documents": [
    {
      "id": "sample-2026-course",
      "raceId": "sample-marathon",
      "year": 2026,
      "kind": "COURSE_GUIDE",
      "distance": "MARATHON",
      "title": "样例马拉松赛道指南",
      "publisher": "样例马拉松组委会",
      "url": "https://example.com/sample/course-2026",
      "publishedAt": "2026-03-02",
      "aliases": ["样例马拉松", "样马"],
      "paragraphs": ["二十公里后的赛道经过一段连续上坡。"]
    }
  ]
}
```

- `id` 在集合内唯一。`raceId` 按赛历中的赛事系列标识填写，同一赛事的不同届次共用。两者最长 80 字符，允许小写字母、数字、连字符和下划线。
- `kind` 取 `RACE_REPORT`、`COURSE_GUIDE` 或 `INTERVIEW`。`year` 是比赛届次，`publishedAt` 是出处的发布日期，格式为 `YYYY-MM-DD`。`url` 使用带主机名且无凭据的 HTTPS 链接。
- `distance` 填 `MARATHON` 或 `HALF_MARATHON`。明确询问某个项目时，只检索同项目资料。省略此字段的赛事资料只在未指定项目的问题中使用。
- `aliases` 提供赛事全称和常见简称。`paragraphs` 按主题保存可逐条复核、适合公开引用的短摘要，每段至多 480 字符。每篇资料最多 12 个别名和 12 段正文，单个文件最多 300 篇、1 MiB。
- 编辑语料时核对出处、届次、赛事项目和正文。只收录公开赛事资料，排除个人跑步活动、路线文件、账号信息和私有书籍内容。

## 检索和验证

索引按中文相邻字符组和英文单词做词项匹配，优先考虑段落正文，最多返回四段。提问可使用赛历确认的赛事标识和明确届次缩小范围。词项匹配依赖原文与提问共享措辞，改写较大的问题可能没有结果。检索过程离线执行，无需 Ollama 或搜索密钥。网站助手对点名赛事的赛道和采访提问检索相应段落，将已核对的公开摘要交给当前聊天模型概括，并把原文链接直接附在回答中。模型不可用时直接展示检索到的资料。

```bash
./gradlew test --tests '*MarathonEvidenceIndexTest'
```

测试通过合成材料验证中英文检索、届次过滤、引用和文件边界。真实资料的来源、时效和内容由编辑人员核对。
