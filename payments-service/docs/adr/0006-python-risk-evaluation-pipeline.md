# ADR 0006: Python-owned deterministic risk evaluation pipeline

## Status

Accepted for Day 12.

## Context

Payment screening needs deterministic, explainable decisions before the fraud
service gains durable feature state on Day 13. Java owns canonical payment and
NameCheck facts. Allowing Java to publish precomputed fraud booleans would split
feature ownership and make rule changes require coordinated releases.

SQS and SNS deliver at least once. A worker can publish a decision and then fail
before acknowledging its input, so delivery cannot be treated as exactly once.
Malformed and version-incompatible messages must not disappear silently.

## Decision

Use a Python-owned `ingest -> enrich -> score -> decision -> audit` pipeline.
Java emits immutable payment facts in a versioned envelope. Python derives
first-time-payee, device-change, and ten-minute velocity features from a history
port, applies versioned additive rules, classifies the score, and emits stable
reason codes. Device input is a pseudonymous token, not a client-supplied
`deviceChanged` assertion.

The version 1 weights are:

| Signal | Score |
|---|---:|
| Amount at least AUD 5,000 | 25 |
| First-time payee | 20 |
| Device changed | 20 |
| Current payment is transaction five or later in ten minutes | 35 |
| Accepted NameCheck mismatch | 40 |

Scores below 40 approve, scores from 40 through 69 hold, and scores of 70 or
more block. Every triggered rule contributes a reason code, and every
non-approval therefore has at least one reason.

Given the same valid event, history snapshot, feature version, and ruleset
version, evaluation produces the same score, action, reasons, and decision ID.
The decision ID is derived from the source event and configuration versions.

The consumer accepts both SNS notifications delivered through SQS and direct
SQS bodies used by tests. It acknowledges a message only after decision
publication, audit, and idempotent history recording succeed. Validation,
unsupported-version, publication, audit, and history failures leave the message
unacknowledged so the queue redrive policy can eventually move it to the DLQ.
Duplicate decision publication is expected; downstream consumers use the stable
decision ID idempotently.

Day 12 supplies ports and in-memory history/audit implementations for seeded,
deterministic scenarios. Day 13 replaces these with immutable PostgreSQL event,
feature, watermark, decision, and inbox records.

## Alternatives considered

- A risk-ready event containing Java-computed booleans is simpler and stateless,
  but divides feature semantics between services and leaves Python owning only
  arithmetic.
- Severity-first rules are easy to narrate, but make the specified numeric
  thresholds largely ceremonial and require separate compound-rule precedence.

## Invariants and consequences

- Java remains the owner of payment facts; Python owns fraud feature semantics.
- Unknown fields, malformed values, and unsupported schema versions fail closed.
- Event time, rather than worker time, defines the velocity window.
- The current payment counts toward the five-transactions-in-ten-minutes rule.
- A first observed device is not considered a device change.
- Day 12 in-memory history is intentionally not restart-safe or suitable for
  horizontally scaled workers; durable ordering and late-event behavior belong
  to Day 13.
