# 赛事动态查询实现计划

> **For agentic workers:** Use superpowers:executing-plans to implement the plan task by task.

目标是在 `/api/v1/chat` 接入范围受控的自动赛事联网查询。Java 21、Spring WebClient、Spring AI 和现有问答契约继续适用。规格见 [赛事动态查询](../specs/2026-11-28-marathon-updates-design.md)。

- [x] 在 MarathonChat 的赛事分流内识别报名、阵容、跑者参赛计划，复用赛历别名和年份。匿名 HTTP 测试验证正常路由、天气与书库隔离以及既有查询优先级。
- [x] 在 MarathonUpdates 内实现缓存、每日额度、单并发和证据校验。离线测试校验预算重置、缓存过期、错届过滤、统计口径、冲突与来源标记。
- [x] 在 MarathonUpdatesClient 内用 WebClient 请求固定 Tavily 地址，Spring AI 提取限定结构。使用替身 HTTP 和模型验证超时降级及查询、来源边界。配置默认关闭并记录部署开关。
- [x] 运行 Gradle 完整构建和 git diff --check，保留现有 RAG 规格草稿。
