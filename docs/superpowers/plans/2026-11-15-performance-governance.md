# RunAgent v0.10 Implementation Plan

> **For agentic workers:** Execute inline using superpowers:executing-plans; the user supplied a complete implementation brief and requested actual implementation.

**Goal:** Measure and govern each multi-agent model invocation without changing other APIs.
**Architecture:** Stage decorators, node-scoped execution-owned budget, immutable safe snapshots and independent test-source benchmarks.
**Tech Stack:** Java 21, Boot 4.1.1, Spring AI 2.0.1, LangGraph4j 1.8.27; no upgrades/dependencies.
**Spec:** ../specs/2026-11-15-performance-governance-design.md

## Global Constraints

BASELINE default; max calls16, output1500, soft tokens30000, existing deadline180s and delegations3. No retry or parallel specialists. Unknown usage/price never zero. No sensitive content in telemetry/reports. No commit/push requested.

## Review Focus

- Graph worker thread changes must install/clear scope per node.
- A failed tool loop must retain consumed calls and preserve earlier successful specialist evidence.
- Missing/provider-normalized Usage must not become free tokens/cost.
- Fallback model pricing must use resolved tier.
- Benchmarks must score actual delegation/tool/completion and preserve dataset identity.

### Task 1: Routing, budgets, usage, cost and context
Files: config/PerformanceProperties.java; performance/*.java; performance/PerformanceCoreTest.java.
Interfaces: ModelRoutePolicy.resolve(ModelStage); ExecutionBudget.reserve(ModelStage); ExecutionScope.open(ExecutionBudget); ModelUsageCollector.record(...); CostEstimator.estimate(...); ContextBudgetPolicy.
- [x] Write tests for routing/caps, reservation/exhaustion, concurrent budgets, UNKNOWN/mixed usage, BigDecimal prices, fallback price, ranked context/Unicode.
- [x] Run focused tests: expect missing-feature failures.
- [x] Implement smallest bounded components; run focused tests green.

### Task 2: Every model call and graph integration
Files: performance/BudgetedChatModel.java, PerformanceGovernance.java, PerformanceSnapshot.java; multiagent/*; observability/RunAgentObservation.java; exceptions/config.
Consumes task1 contracts; produces execution snapshots and unchanged HTTP result.
- [x] Add real ToolCallingAdvisor loop tests, final-slot handoff/no-evidence tests, scope cleanup/deadline/failure and privacy checks; run RED.
- [x] Implement stage clients and node metadata scopes, budget-aware FINISH, telemetry/final snapshots; run tests green.

### Task 3: Shared-dataset offline/live benchmark
Files: test-source performance benchmark harness/report writer; evaluation fixture reuse; build.gradle tasks.
Consumes snapshots and real coordinator, produces safe JSON/Markdown and quality comparison.
- [x] Write report/counting/privacy/quality/opt-in tests; run RED.
- [x] Implement sequential profile runs, warmup/repetitions, safe aggregates and live synthetic-only gateway; run focused tests and performanceBenchmark.

### Task 4: Documentation, version, verification and review
Files: README, AGENTS, docs/performance.md, evaluation/observability, docs/v0.10-verification.md.
- [x] Upgrade only application version, document configuration, architecture and evidence limits.
- [x] Run test, build, evaluation, performanceBenchmark, disabled live task; record actual counts/results.
- [x] Independent whole-change review; fix important findings with regression tests.
- [x] Sync validated task files to original clean RunAgent checkout and rerun gates, git diff --check. Leave uncommitted.

## Execution record

Tasks1–3 implemented and verified. Task4 docs/version and independent review completed; final synchronization/verification recorded in docs/v0.10-verification.md. Baseline225/0/0/2 and evaluation27/0; current253/0/0/2, evaluation27/0, offline benchmark14/14. Live disabled (explicit environment absent). Independent review's three P2 findings have failing regression tests and fixes; no unresolved important findings. No commit/push requested.

Ruling: follow the supplied execution brief without an additional design approval round; preserve its BASELINE default and explicit live/privacy boundaries. Ruling: both profiles apply the requested global output safety cap; document that difference from v0.9 rather than claiming identical behavior. Ruling: fixed synthetic usage/prices/delay validate accounting, never real performance.
