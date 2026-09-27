# RunAgent v0.2 Tool Calling 流程

`/api/chat` 与 `/api/chat/stream` 通过 `RunAgentService` 调用 Spring AI 2.0.1 `ChatClient`，并使用 `.tools(runningTools)` 提供本次请求唯一的 Tool。`/api/intent` 不提供 Tool。

```text
用户：我今年跑了多少公里？
  ↓
RunAgentService：System Prompt 含当前日期，注册 RunningTools
  ↓
第一次模型请求：用户问题 + getRunningSummary 的名称、描述与 JSON Schema
  ↓
模型响应：Tool Call Request，例如 startDate=2026-01-01、endDate=2026-09-26
  ↓
Spring AI ToolCallingAdvisor：解析 Tool Call、匹配并调用 Java Method
  ↓
RunningTools：解析和验证日期，最多 366 天
  ↓
RunningDataService：读取 activities.json，筛选 Run 并确定性聚合
  ↓
Tool Result Message：仅 RunningSummary 或安全错误
  ↓
第二次模型请求：原始对话 + Tool Call + Tool Result
  ↓
最终模型回答：用 Tool Result 解释统计
```

模型只提出 Tool Call，不直接执行 Java 方法。Spring AI 2.0.1 的 `ChatClient` 自动注册 `ToolCallingAdvisor`，它负责工具定义发送、回调匹配、执行、结果消息和再次模型请求。应用没有自定义 `while` 循环。`@Tool` 默认不直接返回，结果会交回模型形成最终回答。流式请求也经过同一个 Advisor；其最终内容继续走现有 SSE `token` 事件。

## 当前 Tool Schema

`ToolCallbacks.from(runningTools)` 从 `@Tool` 和 `@ToolParam` 生成定义。测试检查唯一名称和参数描述。实际 JSON Schema 的关键部分为：

```json
{
  "type": "object",
  "properties": {
    "startDate": {"type": "string", "description": "Inclusive start date in ISO-8601 yyyy-MM-dd format"},
    "endDate": {"type": "string", "description": "Inclusive end date in ISO-8601 yyyy-MM-dd format"}
  },
  "required": ["startDate", "endDate"],
  "additionalProperties": false
}
```

Tool 描述说明它查询用户真实历史跑步统计，适合个人跑量、次数和时长问题；通用知识与训练建议不需要该 Tool。模型把“今年”“这个月”“最近 30 天”等语义转换为日期，Java 再将这些不可信参数按固定格式、顺序和范围校验。

## 可观察性与失败

`RunAgentService` 记录 AI 请求开始与结束；`RunningTools` 记录工具被选择、合法日期参数、执行结果和耗时。数据读取失败会变成固定错误消息，模型必须说明无法获取，不能猜测。未配置的文件、空文件或损坏的 JSON 不能当成“零跑量”；有效 JSON 数组中没有匹配活动时才返回零值。日志和 Tool Result 都不包含活动明细、GPS 或 Polyline。

`RunAgentServiceTest` 用 Stub ChatModel 模拟一次 Tool Call、Tool Result 和第二次模型回答；另一个测试覆盖 Streaming。它们证明本地 Spring AI 调用链和工具注册，不证明真实模型会对每条自然语言问题选择正确工具。真实行为需按 README 配置所选兼容端点、模型和生成后的 `RUNNING_DATA_PATH`，再用 curl 命令和安全日志观察。

## 动手实验：自己串一次 Tool Calling

[ManualToolCallingFlowTest](../src/test/java/cn/watsonzhu/runagent/lab/ManualToolCallingFlowTest.java) 只放在 `src/test`，不接入 `/api/chat`。它直接调用低层 `ChatModel.call(Prompt)`，以便看清高层 `ChatClient` 隐藏的步骤。先运行不需要模型 Key 的脚本模型实验：

```bash
./gradlew test --tests 'cn.watsonzhu.runagent.lab.ManualToolCallingFlowTest.scriptedModelShowsTheCompleteManualRoundTrip' --rerun-tasks --info
```

在测试的标准输出中按顺序找这些阶段（Gradle 默认隐藏通过测试的标准输出，所以加 `--info`）：

```text
第 1 次 LLM Request
Tool Call: getRunningSummary
Java Execute
Tool Result Message
第 2 次 LLM Request
Final Answer
```

建议沿着测试中的 `runManually()` 单步阅读：

1. `ToolCallbacks.from(runningTools)` 只生成并提供 Tool Schema。实验没有让回调自动执行工具。
2. `model.call(new Prompt(messages, options))` 发出第一次请求。脚本模型模拟返回带 `toolCalls` 的 `AssistantMessage`。
3. `response.hasToolCalls()` 决定直接回答，还是进入工具分支。先检查工具名称和 call id，再解析日期参数；`RunningTools` 继续做业务校验。
4. Java 调用 `RunningTools.getRunningSummary()`，只把 `RunningSummary` 序列化成安全的工具结果。
5. 把原始 `AssistantMessage` 和带相同 call id 的 `ToolResponseMessage` 依次加入消息列表，再调用一次模型取得最终回答。

实验最多允许三次模型请求和三次工具调用，以免示例意外无限循环。另两个测试展示“模型直接回答，无需工具”和“拒绝未注册工具”。脚本模型验证的是**协议与 Java 执行顺序**，不验证真实模型的工具选择能力。

若要观察真实模型提出 Tool Call，可显式启用可选测试，并提供所选兼容端点的配置（环境变量或被忽略的本地 profile）：

```bash
export RUN_TOOL_LOOP_LIVE=1
SPRING_PROFILES_ACTIVE=local ./gradlew test --tests 'cn.watsonzhu.runagent.lab.ManualToolCallingFlowTest.liveModelShowsTheSameManualRoundTrip' --rerun-tasks --info
```

这会真实调用模型，可能产生费用。**现场实验强制使用仓库内的合成 fixture**，即使设置了私人 `RUNNING_DATA_PATH` 也不会读取真实跑步文件；实验 Prompt 也明确告诉模型这些不是用户的真实历史。测试只打印阶段，不打印活动数据、Tool Result 或最终回答；未设置 `RUN_TOOL_LOOP_LIVE=1` 时普通测试会跳过它。若用环境变量提供 Key 而非本地 profile，可以省略 `SPRING_PROFILES_ACTIVE=local`。

对照生产代码：实验里的 `model.call`、`hasToolCalls`、Java 分发、`ToolResponseMessage` 和下一次 `model.call`，在 `RunAgentService` 的 `ChatClient.call()` 中由 Spring AI 2.0.1 的 `ToolCallingAdvisor` 管理。模型只提出调用请求，执行权始终在 Java 应用。
