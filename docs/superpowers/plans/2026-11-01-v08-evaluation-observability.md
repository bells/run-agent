# RunAgent v0.8 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline; the user approved direct implementation. No intermediate approval or automatic commits.

**Goal:** Establish separate offline quality evaluation and privacy-safe runtime observability.
**Architecture:** JavaExec runners on test runtime classpath call real Java components with synthetic dependencies; production uses Boot/Spring AI instrumentation plus minimal business observations.
**Tech Stack:** Java 21, Gradle 9.7.1, Boot 4.1.1, Spring AI 2.0.1, LangGraph4j 1.8.27.
**Spec:** `docs/superpowers/specs/2026-11-01-v08-evaluation-observability-design.md` (approved).

## Global Constraints

- Version 0.8.0-SNAPSHOT; no dependency platform upgrades or cross-repository edits.
- Normal test/build never calls real LLM, public MCP, Ollama or private books.
- No raw content in reports/observations; stable low-cardinality meter tags only.
- Live/Judge opt-in; live report only, offline thresholds fail only after reports exist.
- No commit/push/tag/release without user instruction.

## Review Focus

- Repeated unordered tool calls must be matched one-to-one, preserving multiplicity.
- Empty labels are unjudged, not perfect recall; duplicate retrieval IDs cannot inflate scores.
- Error/timeout/cancellation paths stop observations without leaking exception payloads.
- Docker loopback scrape must not cause management to bind all interfaces.
- Private golden/baseline metadata must not leak question/evidence or compare incompatible runs.

### Task 1: Evaluation metrics and reports

**Files:** `src/test/java/cn/watsonzhu/runagent/evaluation/{EvaluationModels,EvaluationMetrics,EvaluationReportWriter,EvaluationMetricsTest,EvaluationReportWriterTest}.java`.
**Interfaces:** `ToolCall(name, arguments)`, `ToolCase(id, question, ordered, allowedToolSets)`; retrieval score; immutable per-case/report records. Report writer receives Path and report, never prompts.

- [x] Write metric tests for wrong/extra calls, allowed alternatives, order, repeated calls, numeric/ISO arguments, Recall/Precision/RR rank 1/2/5, empty/unjudged and duplicate IDs; run RED.
- [x] Implement explicit models and metric calculations; run metric tests GREEN.
- [x] Write report aggregate/privacy/checksum/baseline tests in temporary directory; run RED.
- [x] Implement safe JSON/Markdown writer and compatible baseline comparison; run tests GREEN.

### Task 2: Offline JavaExec evaluation

**Files:** `build.gradle`, `src/test/java/cn/watsonzhu/runagent/evaluation/{EvaluationRunner,EvaluationFixtures,RecordingToolCallback,ScriptedChatModel}.java`, `src/test/resources/evaluation/*.json`, `EvaluationRunnerTest.java`.
**Interfaces:** runner returns report; fixtures create actual RunningTools, KnowledgeSearchService and TrainingAnalysisWorkflow with synthetic dependencies; recorded actual calls feed Task 1 metrics.

- [x] Write integration tests for versioned datasets, actual callbacks, three graph modes, report generation and failing gate; run RED.
- [x] Implement ~12 fixed-date tool cases, corpus/rag queries, three routes, model scripts separate from expectations, synthetic activities; implement runner.
- [x] Register `evaluation` JavaExec; run runner tests and task GREEN; explicitly label SCRIPTED_MODEL_CONTRACT and FAKE_EMBEDDING evidence.

### Task 3: Runtime observations and Boot configuration

**Files:** `build.gradle`, `observability/RunAgentObservation.java`, Agent/Workflow/WorkflowExecutionTrace/AnalysisNode/KnowledgeSearchService/KnowledgeIndexService/RagConfig, `application*.yml`, observability tests.
**Interfaces:** helper observes supplier with fixed tags/outcome and records per-request tool count/RAG count; injected registries instrument independently-built ChatClient/VectorStore.

- [x] Resolve exact Boot 4.1.1 starter/metadata and Spring AI 2.0.1 APIs without platform upgrades.
- [x] Write tests for Agent 3 calls one sample, five combined nodes, failed node stop, RAG 3/0/unavailable, no dynamic metric tags, actual Analysis/VectorStore observations; run RED.
- [x] Implement helper/integration while preserving cancellation, prompt isolation and index fingerprint; run GREEN and full tests.
- [x] Configure safe default management/exporter/privacy, opt-in observability/histograms; write binding/endpoint tests, run GREEN.

### Task 4: Live evaluation, optional Judge and local golden

**Files:** `LiveEvaluationRunner.java`, `LiveEvaluationSettings.java`, `LocalRagEvaluation.java`, their tests, `build.gradle`.
**Interfaces:** explicit env/settings; synthetic callbacks + target model; optional evaluators; local-only golden retrieves through existing index and emits metadata-only scores.

- [x] Write default-disabled/missing-config/bounded-call/Judge/private-label tests without real network; run RED.
- [x] Implement real-model runner and opt-in Judge using verified 2.0.1 evaluators; finite case deadlines/call limits; safe report-only failures.
- [x] Implement private retrieval and baseline compatibility; no private content sent to target/Judge.
- [x] Register `liveEvaluation`; test default skip and fake live execution GREEN.

### Task 5: Stack, docs, verification and review

**Files:** `compose.observability.yml`, `observability/prometheus.yml`, README, AGENTS, `docs/{evaluation,observability,v0.8-verification}.md`.

- [x] Verify fixed official Jaeger/Prometheus releases; configure loopback-only host-network learning stack and document prerequisites/native alternative.
- [x] Update current version/roadmap and learning explanations, configuration, metrics, P95 and evidence limits.
- [x] Run `./gradlew test build evaluation liveEvaluation`, dependency inspection, whitespace/privacy review; record XML counts and actual report/metrics evidence.
- [x] Fresh whole-change code review, fix meaningful findings with regression tests, rerun required verification.
