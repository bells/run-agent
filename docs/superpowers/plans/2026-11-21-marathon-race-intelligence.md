# Marathon Race Intelligence Implementation Plan

> **For agentic workers:** Use superpowers:test-driven-development and verify each component and the combined build.

**Goal** 让现有 RunAgent 问答可查三个月赛事日历和官方领奖台成绩，并给站主提供有界缓存回填。

**Architecture** 首版在同一个 JVM 内提供赛事服务。Python 命令按稳定届次键更新只读 JSON 快照。Java 按赛事届次与项目存放独立成绩文件，通过站主鉴权限制联网回填，两个聊天入口共享本地服务。

**Tech Stack** Java 21、Spring AI 2.0.1、WebFlux、Jackson 3、Gradle、Python 3。

**Spec** [RunAgent 赛事日历与官方成绩](../specs/2026-11-21-marathon-race-intelligence-design.md)

## 通用约束

- 确定性结果使用赛事时区和可注入 Clock。官方成绩需要可追溯证据，缺少字段用空值。全马和半马独立键。
- 现有请求、授权、并发限制和工作流兼容。访客与旧版 Agent 只能从本地读取。
- 功能以及联网回填独立配置。自动测试只有合成来源和替身，无真实数据或模型依赖。

## 实施与验收顺序

1. **数据契约、快照和同步。** 在 `marathon/MarathonData.java` 定义系列、届次、榜单和来源记录。在 `MarathonCalendar.java` 安装经验证快照和进行过滤。先测试重庆 12 月查询为 36 天、同城全半马分离及无效快照保留。Python CLI 查询公开赛历，配置选定系列，测试合并、来源故障和原子发布。以有效 JSON 种子统一 Java/Python 的快照契约。
2. **官方结果取证。** `MarathonResultLookup.lookup(series, event, year, distance, deadlineNanos)` 以一个受限 Tavily 查询发现官方页面并解析证据。对伪造用时、合法排名串行以及跨届次或项目的证据写先失败测试。模型调用输出结构化候选，独立验证，只有能在官方正文同一记录中对上的条目才进入缓存。
3. **缓存与服务。** `MarathonService` 提供赛历、成绩及选手查询，`MarathonPodiumStore` 做原子写入与有界读取。先测试省略年份、未来届次、访客未命中、站主补齐、并发单飞和重启读取，随后实现缓存、冷却、刷新及独立配置。
4. **真实入口。** 为旧版 Agent 注册两个 Spring AI callbacks。版本化聊天按事件意图查询证据，纯事实由 Java 生成，其他问题沿用现有回答链。先测试访客能查已核验赛历、错误令牌仍拒绝和纯查询无需模型；复合提问只使用预报支持的时段。
5. **发布契约与验证。** 给出启用、卷、调度及有密钥的单次 smoke 命令。运行 `./gradlew build`、Python 离线测试、部署脚本验证及 `git diff --check`。报告真实源站和线上验收中的剩余项。
