# RunAgent v0.3 Agent Loop

## 当前实现

`POST /api/agent` 由 `AgentController` 校验输入、把阻塞调用放在 `boundedElastic`、施加请求级 deadline。`RunAgentService.agent` 为每个请求创建 `AgentExecutionTrace`，通过 `ChatClient.toolContext` 传给三个 `RunningTools`，并返回执行 ID、实际工具执行次数和最终文本。

```text
Model Request → Tool Call Request → Java Tool → Tool Result / Observation
      ↑                                                │
      └──────────── ToolCallingAdvisor ────────────────┘
                    模型不再请求 Tool → Final Answer
```

Spring AI 2.0.1 的 `ChatClient` 构建请求并注册 Tools。`ToolCallingAdvisor` 负责递归模型调用和停止判断。`ToolCallingManager` 解析 Tool 定义、验证调用次数、执行 Java callback，并生成包含 Tool Result 的会话历史。生产代码只调用 `chatClient.prompt().system(...).user(...).tools(runningTools).toolContext(...).call().chatResponse()`，没有自定义 `while(true)`。

概念上，Advisor 的流程相当于：

```java
response = model.call(prompt);
while (response.hasToolCalls()) {
    result = toolCallingManager.executeToolCalls(prompt, response);
    prompt = new Prompt(result.conversationHistory(), options);
    response = model.call(prompt);
}
return response;
```

这段代码只解释框架内部行为，不进入生产路径。模型可先选择最近 30 天 Summary，看到结果后再选择上一段 30 天 Summary，随后请求最近 5 次 Run，最后返回答复。同一 Tool 可在一轮请求中被调用多次。

## 停止与安全边界

停止条件：模型返回普通 Final Answer；框架 Tool Call Limit 超出；请求 deadline 到期；或不可恢复异常。Spring AI 的 `spring.ai.tools.limits.max-calls-per-tool-default=4` 和 `max-total-tool-calls=8` 由自动配置绑定到 `ToolCallingManager`，`on-limit-exceeded=THROW`。2.0.1 的 Advisor 捕获超限异常，并返回 finish reason `toolCallLimitExceeded`；服务检查该标记，给客户端安全错误。未设调用限制时，模型可能重复请求 Tool，造成 token、API 费用、延迟和外部系统压力；只读 Tool 也需要边界。

`run-agent.agent.timeout=30s` 由 `AgentProperties` 管理。`Mono.timeout` 是应用层 deadline 和尽力取消，不保证中断底层阻塞 Provider HTTP 请求。因此在 deadline 后仍可能有已启动的模型请求或工具调用结束；超时响应不会返回其结果。

## Trace 与数据边界

ToolContext 是 Spring AI 的应用侧上下文，不进入模型可见的 Tool JSON Schema。三个 Schema 只包含 `startDate`、`endDate`、`limit` 或 `distanceType`。每个 Agent 请求通过 ToolContext 持有独立 trace，无 `ThreadLocal` 或全局可变 Map。每次 Tool 真正进入 Java 方法时，原子计数器生成递增 step。它记录 Action / Observation 边界，不记录隐藏推理、消息全文、Prompt、原始 Tool Result、轨迹或密钥。

日志结构示例，属于格式展示，不代表真实供应商调用：

```text
agentExecutionId=<uuid> phase=STARTED
agentExecutionId=<uuid> step=1 tool=getRunningSummary phase=EXECUTING
agentExecutionId=<uuid> step=1 tool=getRunningSummary status=SUCCESS latencyMs=...
agentExecutionId=<uuid> step=2 tool=getRunningSummary status=SUCCESS latencyMs=...
agentExecutionId=<uuid> step=3 tool=getRecentRuns status=SUCCESS latencyMs=...
agentExecutionId=<uuid> phase=COMPLETED toolCalls=3 latencyMs=...
```

Java 负责日期和 limit 校验、过滤 `Run`、排序、距离与时长单位转换、配速计算及 PB 匹配。LLM 决定获取哪些事实并解释它们。PB 仅是整次活动级近似成绩，不能称为精确分段 PB。

## 阶段边界与验证

本页记录 v0.3 的单次请求 Agent Loop。v0.4 已为 `/api/agent` 增加有界跨请求 Conversation Memory；详见 [Conversation Memory](memory.md)。跑步数据仍是结构化 JSON，经 Tool 读取；没有 Embedding、Retriever、RAG 或多 Agent。

自动测试覆盖 Tool Schema、数据计算、请求校验、三次动态 Tool 调用、框架超限标记和请求超时，不触发真实模型。实际模型可能并行请求多个 Tool，或以不同顺序调用；真实验收需要启动服务、发出比较问题，并核对本次请求的 `executionId`、日志步骤、`toolCallCount` 与回答内容。
