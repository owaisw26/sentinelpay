# ADR 0005: Database limits and fail-closed NameCheck degradation

## Status

Accepted for Day 11.

## Context

Payee checks and payment creation need limits that cannot be bypassed by sending
requests to another service instance. Name verification also needs bounded local
latency without allowing an unavailable verifier to become an implicit approval.

## Decision

Use a PostgreSQL fixed-window counter keyed by customer and operation. PostgreSQL
calculates the window from its own clock and updates the counter with one atomic
upsert. Limit accounting runs in an independent transaction, so a later request
failure cannot erase the attempt. Rejected requests return `429` and the database-
derived number of seconds until the next window in `Retry-After`.

Cache NameCheck match results in a bounded Caffeine cache keyed by registry
version, receiver wallet, and canonical supplied name. Entries expire after a
short configurable TTL and are explicitly invalidated when a registry name is
replaced. The registry version remains part of the key as a second correctness
barrier.

If the verifier is unavailable on a cache miss, allow only a prior `MATCH` for
the same customer, receiver, registry version, and canonical-name hash created
within 24 hours. Persist the new check with `DEGRADED_REUSE` provenance. All
first-time, renamed, mismatched, and older checks fail closed with `503`.

## Alternatives considered

- Per-instance token buckets are lower latency but multiply the effective limit
  as instances scale and lose state during restarts.
- Redis supports distributed token buckets and smoother bursts, but introduces
  another stateful dependency before the project otherwise needs Redis.
- Reusing any recent check during an outage improves availability but permits a
  different supplied name or registry version to bypass verification.

## Invariants and consequences

- A user cannot exceed the configured operation count by changing instances.
- Cache data cannot survive its TTL or a receiver registry version replacement.
- Verifier failure never establishes a new customer/name/payee trust relation.
- Fixed windows permit a boundary burst of up to twice the configured maximum;
  move to a Redis token bucket if traffic shaping, rather than abuse containment,
  becomes the primary requirement.
