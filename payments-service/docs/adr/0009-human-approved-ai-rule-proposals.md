# ADR 0009: Human-approved AI rule proposals

## Status

Accepted for Day 15.

## Context

SentinelPay needs to demonstrate how an AI model can assist fraud analysts
without allowing probabilistic output to become executable policy. Structured
output can constrain JSON shape, but it cannot establish that a threshold is
safe, that a rule is sufficiently narrow, or that the rule improves outcomes.
The fraud service already owns the five online risk features, a seeded
synthetic dataset, an anomaly model, immutable decisions, and versioned audit
metadata. Java remains the authenticated analyst-facing boundary.

## Considered alternatives

1. Allowlisted rule DSL. The model proposes conjunctions of known features,
   typed operators and bounded values. This is more expressive but requires a
   complete semantic validator and a non-dynamic interpreter.
2. Server-owned templates. The model selects a prebuilt template and bounded
   parameters. This has a smaller attack surface but cannot propose genuinely
   new feature combinations.

## Decision

Use the allowlisted DSL. Cluster only labelled synthetic training fraud cases
locally and send aggregate cluster statistics plus an aggregate legitimate
baseline. Never send transaction, customer, wallet, payee, or device
identifiers. Use the OpenAI Responses API with a pinned, configurable model,
strict JSON Schema output, `store=false`, no tools, bounded output, an explicit
timeout, and no more than two retries for rate-limit or server errors. CI uses
a deterministic contract stub.

Model output is untrusted input. Validate feature/operator/value compatibility,
threshold bounds, rule counts, condition counts, reason codes, referenced
clusters, actions, and expiry locally. Compile no code and call no `eval`;
trusted Python compares each condition with the already-computed `RiskFeatures`.
Simulate every valid proposal against the held-out synthetic validation set and
persist the resulting impact report.

Generation can create only `DRAFT` or `VALIDATION_FAILED`. Only an authenticated
analyst can approve a draft using its expected version. Approval revalidates the
stored candidate, creates a new immutable ruleset, and atomically advances the
single active-ruleset pointer. Deactivation and rollback also create new
versions. Rollback copies an earlier version's contents rather than rewriting
history.

Approved dynamic rules may only produce `HOLD` or `BLOCK`. Their result is an
action floor, so they can never weaken a deterministic or anomaly decision.
Only payments finalized after activation use the new version; past decisions
are immutable. Every decision records the combined deterministic/dynamic
ruleset version and matched rule IDs.

## Consequences

- Analysts can inspect provenance and measured synthetic impact before
  activation.
- A compromised OpenAI credential cannot activate a rule.
- The DSL interpreter and semantic validator become security-critical code and
  require exhaustive boundary tests.
- A single analyst may approve in this portfolio version. A production rollout
  should add dual control, staged shadow evaluation, rule-level volume limits,
  and automated rollback thresholds.
