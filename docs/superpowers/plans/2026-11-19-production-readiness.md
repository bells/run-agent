# Watson Running 线上完善计划

> 实现按三个仓库的独立构建和验证边界推进。每项行为变更先验证回归测试能失败，再实现和全量复验。

**目标**让 Watson 能在私有网页助手查询真实训练统计，并保留可独立验证的发布、回滚、数据更新和简历演示证据。

**设计**延用 GitHub → Vercel 和香港 Docker Compose。通用 Chat 请求通过现有版本化 API 访问 DeepSeek。训练统计走独立私有 API，RunAgent 在服务端调用同机 MCP，直接返回类型化统计结果。合成数据演示随项目文档发布。

## 约束

- 先用合成数据验证授权、日期边界、计算、空区间、快照缺失、MCP 故障和重启。真实快照按已同意的范围上传香港，仅供 MCP 读取。
- 私有快照仅含内部活动 ID、活动类型、活动日期、距离和移动时长。公开站点产物走原现有发布链路，并单独核查短路线隐私。
- 个人训练端点的密钥、参数、结果、原始轨迹、ID 映射和日期明细不进入日志、错误响应或外部模型。
- 同机私有生产模式关闭旧版模型入口，回滚到旧版本时隔离真实快照。新 API 默认关闭。沿用私有聊天令牌。保留现有聊天契约和三个 MCP 工具名称。发布和数据回滚分别验证。
- 三仓各自的 PR 和 CI 独立验证。真实上线以前端 GitHub 合入授权、香港可访问、合成闭环和回滚通过为前提。

## 训练统计契约

`POST /api/v1/training/stats` 与现有 Chat 使用同一 Bearer 令牌，响应带 `Cache-Control: no-store` 和 `requestId`。请求支持两种严格结构：

- `{"kind":"SUMMARY","startDate":"2026-11-01","endDate":"2026-11-07"}`。日期包含首尾，最多 366 天。响应包含 `kind`、`requestId`、`dataThrough`、`startDate`、`endDate`、`runCount`、`totalDistanceKm`、`totalDurationSeconds` 和可空的 `averagePaceSecondsPerKm`。后者用总移动秒数除以总公里数并四舍五入。
- `{"kind":"PERSONAL_BEST","distanceType":"FIVE_K"}`。支持 `FIVE_K`、`TEN_K`、`HALF_MARATHON`、`MARATHON`。响应包含 `kind`、`requestId`、`dataThrough`、`distanceType`、`approximate: true` 和 `result`。匹配时 `result` 提供 `date`、`activityDistanceKm`、`durationSeconds`、`averagePaceSecondsPerKm`。无匹配时为 `null`。
- `dataThrough` 表示快照内最近活动日期，为 ISO 日期。空快照或兼容旧 MCP 响应时为 `null`。
- 错误响应复用 `requestId`、`code`、`message`。验收 400 `INVALID_REQUEST`、401 `UNAUTHORIZED`、503 `TRAINING_DATA_UNAVAILABLE`。有数据的空区间正常返回零。

## 工作顺序与验收

1. **Running MCP Server**。在现有汇总和 PB 返回值增加 `dataThrough`。测试用合成快照覆盖三工具及数据不可用，补 Java 21 CI。兼容日期与已有本地时间字段。
2. **RunAgent**。实现统计路由、共享 Bearer 校验、日期与距离校验和已有 MCP 目录调用。统计失败继续允许通用问答。验证指标元数据避免携带活动数据，构建版本与生产阶段对齐。
3. **Watson Running**。Classic 助手加入「我的训练」，提供 7/30 天与自选日期、PB 距离、活动截止日期和明确的空／错误状态。精确同源转发，使用现有会话令牌。用 mock 浏览器验证两种查询和出错后重试。修正短路线端点发布，用合成轨迹覆盖临界距离。
4. **发布与回滚**。从两仓选定提交打包并记录完整 SHA。容器健康门禁检查 MCP 目录能力，合成闭环额外检查实际工具结果。用失败候选验证发布指针回退。加入安全的生产探测，至少覆盖站点、匿名 API 和受控的统计 smoke。验收后导入真实快照并测试原子替换与上一快照恢复。
5. **证据与简历**。README 保持生产项目定位。记录三仓版本、端到端验收、故障演练和监控数据来源。公开演示用标注的合成输入，公开材料不使用私人令牌或个人快照。
