# RunAgent v0.10 Performance & Cost Governance

The supplied v0.10 brief is the implementation contract. Preserve v0.9 evidence at f0c8fff: 122532ms real total, Knowledge model 56170ms, Synthesis 23342ms; neither payloads nor answers belong in performance data.

## Approach

Use stage-specific ChatModel decorators around the existing shared gateway model. ToolCallingAdvisor invokes this boundary on every synchronous loop iteration. An execution-owned budget/collector travels in local RunnableConfig metadata; each graph node installs and clears a strict ThreadLocal scope. This is safer than wrapping only the caller thread because graph execution can move threads. Other APIs keep their existing model.

Alternatives considered: ChatClient-level accounting misses internal calls; a global model wrapper changes unrelated APIs. Neither meets the brief. No new runtime dependency, provider, tool, agent, parallelism, cache or retry.

## Contracts

Fixed stage/tier/mode enums; trusted configuration resolves model and output cap. BASELINE uses DEFAULT and existing context bounds; OPTIMIZED uses FAST/FAST/DEFAULT/FAST/STRONG and stage caps 256/800/1000/800/1200. FAST/STRONG missing configuration resolves DEFAULT and logs fallback. The global 1500 output guard applies in both modes and is an intentional safety difference from v0.9.

ExecutionBudget composes the existing 180s control, counts provider attempts before invocation, reserves one synthesis call and stops optional work after known usage crosses 30000. Existing maxDelegations remains authoritative. Unknown/invalid/empty usage is nullable UNKNOWN, incomplete totals never become complete cost. BigDecimal pricing is configured per resolved tier, negative means unknown; cache usage without cache prices makes cost unknown.

Context is bounded by characters, never advertised as exact tokens. Routing alone projects 600-char reports; synthesis retains full 2500-char reports. Knowledge baseline is 12000, optimized 9000, ranked document boundaries/source headers are preserved, retrieval TopK unchanged. Task limit remains 2000.

Every execution publishes an immutable internal snapshot in finally. Observations use enum tags and safe status/reason; actual model/id only high-cardinality. HTTP response stays unchanged. Synthetic/live benchmark reuse the seven v0.9 multi-agent cases and independent scripts, real graph/clients/tool loops and synthetic local retrieval. Reports store metadata/metrics only. Live is explicitly enabled, one warmup plus three measured repetitions, same gateway, no local secrets/real data reads.

## Verification

Test routing, per-call caps, three-call real tool loop, reservation, exhaustion with/without evidence, unknown/mixed/failing usage, cost math/fallback price, document bounds/Unicode, simultaneous requests and scope cleanup, deadlines, telemetry/report privacy, report statistics and live opt-in. Run full test/build/evaluation/performanceBenchmark; live only if explicit environment is ready. Scripted quality is a contract gate, never proof of real model answer quality.
