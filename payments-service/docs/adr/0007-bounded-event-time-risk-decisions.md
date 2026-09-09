# ADR 0007: Bounded event-time risk decisions

## Status

Accepted for Day 13.

## Context

Fraud events arrive through SNS and SQS, which provide at-least-once delivery
without global ordering. Processing strictly in arrival order makes rolling
features depend on transient queue timing. Waiting for every possibly earlier
event, however, can starve a quiet customer stream forever.

The Day 12 worker also performs publication, audit, and history mutation as
separate in-memory effects. A crash between those effects can replay work after
a restart, and multiple workers do not share feature state.

## Decision

The fraud service stores each valid event immediately and finalizes it only
after a bounded event-time deadline. The default allowed-lateness interval is
30 seconds and remains configurable. An event is eligible when database time is
at or beyond `occurred_at + allowed_lateness`; equivalently, the wall-clock
watermark is database time minus the allowed-lateness interval. This watermark
continues to advance for idle customers and does not wait for a missing
sequence number.

Feature windows use trusted producer event time. Events received before a
decision's deadline may contribute to it. An event received after the deadline
is retained and marked late, but cannot rewrite an existing final decision. It
may contribute prospectively to decisions whose deadlines have not passed.

Received events, feature snapshots, watermarks, versioned decisions, and a
decision outbox live in a dedicated PostgreSQL `fraud` schema. Input recording
and decision finalization are transactional. SNS publication occurs from the
durable outbox and is retried with the stable decision ID.

Java records decisions in a durable inbox before applying them. It locks the
payment and requires the decision's source aggregate sequence to equal the
payment's expected screening sequence. Duplicate decisions are no-ops. Stale
decisions and decisions for terminal payments are audited as ignored and never
change money or state. An approval reserves funds and moves the payment to
processing atomically before a provider message is emitted.

`aggregateSequence` orders events for one payment; it does not claim to order
all payments belonging to a customer. Customer rolling features are ordered by
event time with deterministic UUID tie-breaking.

## Alternatives considered

- Immediate immutable evaluation has lower latency, but a normally delayed
  earlier event cannot contribute to a decision even when it arrives moments
  later.
- Unbounded waiting for event-time completeness cannot establish that no
  earlier event remains in flight and can indefinitely block payment
  processing.

## Invariants and consequences

- Every accepted screening event has at most one immutable final decision for
  a feature/ruleset/model version tuple.
- Replaying the same event is a database no-op and cannot alter rolling state.
- Late events are observable and retained, but never revise final decisions.
- One missing event can leave its payment fail-closed in `SCREENING`; it cannot
  block another payment, customer, or worker batch.
- Producer timestamps must come from the authenticated Java service. Excessive
  future timestamps are rejected so a publisher cannot stall the watermark.
- The lateness bound trades payment latency for more complete event-time
  features and must be monitored against queue-delay evidence.
