# v0.11 Reliability & Failure Governance

RunAgent v0.11 incrementally adds reliability and workflow recovery to the completed v0.10 performance governance baseline. This design records the implementation boundaries for the learning milestone.

## Intent and constraints

Preserve v0.10 default semantics, opt in to bounded dependency protection, keep exactly one retry owner and task-wide deadline/budget accounting. No provider switching, parallel specialists, production chaos endpoints or write tools. Historical v0.10 measurements remain unchanged.

## Approach

Use programmatic Resilience4j 2.4.0 core primitives: Retry -> dependency-scoped CircuitBreaker -> semaphore Bulkhead -> actual BudgetedChatModel / accepted MCP callback. An explicit executor releases permits before backoff. Typed failures become safe fixed categories, never retained response bodies. RAG retrieval has no retry. LLM retries individually enter BudgetedChatModel and preserve synthesis reservation and UNKNOWN usage/cost.

Alternatives considered: annotations hide budget/order boundaries; a second TimeLimiter duplicates existing deadlines; a custom persistence database exceeds the learning scope. These are rejected by the user's supplied constraints.

Fallback is deterministic Java policy; partial evidence may be synthesized with explicit limitations, no evidence fails safely. Route and synthesis remain critical. No internal report concatenation fallback.

Workflow uses the official 1.8 LTS saver and real resume API, under an independent opt-in flag. Prefer official durable saver only if safe state serialization fits the existing types; otherwise MemorySaver with explicit JVM-lifetime limits. Checkpoint state must exclude raw RAG text and runtime control. Recovery remains a Java service, no public HTTP contract change.

## Verification

Offline deterministic injection covers typed HTTP faults, budget/deadline gates, real circuit transitions, latch-based bulkhead concurrency, permit release during backoff, partial/all failure evidence, checkpoint failed-node resume and no duplicate completed nodes. Tests, build, evaluation, performanceBenchmark and independent reliabilityVerification must pass. Never call live providers for fault injection.
