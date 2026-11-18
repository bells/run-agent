# v0.11 Reliability Implementation Plan

> **For agentic workers:** Use the executing-plans workflow and dispatching-parallel-agents for the two disjoint implementation domains below. Keep a local ledger under `.run-agent/v011/`.

**Goal:** bounded failure protection and safe workflow recovery without breaking v0.10 budgets or evidence semantics.
**Architecture:** explicit dependency protection outside per-attempt budgeted calls; deterministic fallback; official checkpoint saver.
**Tech Stack:** Java 21, Spring AI 2.0.1, Resilience4j 2.4.0, LangGraph4j 1.8 LTS.
**Spec:** `docs/superpowers/specs/2026-11-16-reliability-design.md` and user-supplied request.

## Global Constraints

- Reliability and checkpoint default false; Spring AI max-retries stays 0.
- Exactly one retry owner; maxAttempts=2 includes initial attempt.
- Same deadline and ExecutionBudget across retries; final synthesis reservation remains.
- Fixed dependency/category telemetry, no user or dependency content.
- No external services in tests or verification. No push/release.

## Review Focus

- Wrapped typed provider failures remain classifiable without retaining private bodies.
- Circuit/bulkhead rejections do not consume provider-call budget.
- Cancellation during backoff prevents a second attempt.
- Recovery cannot persist raw evidence or repeat completed nodes.
- Default off preserves all old API contracts.

### Task 1: Baseline and dependencies (root)
- [x] Verify clean starting commit and baseline test/build/evaluation/performance.
- [x] Add exact Resilience4j core dependencies and inspect runtime graph.
- [x] Upgrade application to 0.11.0-SNAPSHOT without Spring AI change.

### Task 2: Reliability primitives (dependency agent, disjoint reliability package + budget seam)
- [x] Write failing deterministic tests for classification/retry/deadline/budget/circuit/bulkhead/order.
- [x] Implement validated configuration, executor, model/tool wrappers, fixed metrics.
- [x] Run focused tests, record actual outcomes.

### Task 3: Workflow recovery (recovery agent, workflow package only)
- [x] Inspect official 1.8.27/28 APIs and patch release evidence.
- [x] Write failed-node recovery test first; implement official Saver/resume.
- [x] Prove prior Running executes once and raw evidence is excluded.
- [x] Verify disabled mode and lifecycle/concurrency boundaries.

### Task 4: Runtime integration and fallback (root)
- [x] Inject one shared executor into all multi-agent models/MCP attempts and RAG retrieval.
- [x] Preserve original tool identity, host-only context and hints before retries.
- [x] Test deterministic fallback, critical failures, all-evidence failure and healthy-call parity.

### Task 5: Offline fault verification and documentation (root)
- [x] Build independent reliabilityVerification runner with safe JSON/Markdown reports.
- [x] Record actual scenario outcomes, budget/privacy checks.
- [x] Update README/AGENTS/reliability/performance/evaluation/observability/verification docs.
- [x] Run complete checks and fresh-context review, resolve material findings.
- [x] Review diff/security and leave changes on the feature worktree for inspection (no push).
