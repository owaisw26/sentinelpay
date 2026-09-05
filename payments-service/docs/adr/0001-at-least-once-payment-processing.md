# ADR 0001: At-least-once payment processing with idempotent effects

- Status: Accepted
- Date: 2026-09-05

## Context

SQS provides at-least-once delivery. A worker can send a payment to the PSP and
then lose the response or crash before recording it. The message will be
delivered again, but SentinelPay cannot atomically commit one transaction across
PostgreSQL, SQS, and the external PSP.

Message deduplication, operation idempotency, and exactly-once business effects
address different boundaries:

- The event ID identifies a delivered message.
- The internal payment ID identifies the logical PSP operation.
- Database state transitions and unique constraints protect financial effects.

## Decision

Use standard SQS and accept duplicate delivery. Coordinate processing through
durable PostgreSQL inbox records and leases. Every PSP retry for a payment uses
the server-generated internal payment ID as its stable provider idempotency key.
Reservation, capture, release, and settlement remain guarded by database locks,
state checks, and uniqueness constraints.

If a PSP outcome is unknown, retain the reservation and retry only with the same
provider key. Reconciliation resolves outcomes that cannot be established by a
normal retry.

The core invariant is: one internal payment maps to at most one PSP operation
and one financial settlement, regardless of message delivery count.

Infrastructure ownership is deliberately outside the application. The
LocalStack ready hook owns local queue, DLQ, and redrive-policy creation;
Terraform will own the equivalent AWS resources from Day 17. The service only
consumes injected queue URLs and never creates or mutates queues at runtime.

## Alternatives considered

Use an SQS FIFO queue with one message group per payment. FIFO ordering and
deduplication would reduce concurrent duplicate deliveries, but would not make
the PSP call and database commit atomic. Its deduplication window also cannot
protect delayed retries or contradictory events. Application-level idempotency
and financial constraints would still be required, so standard SQS is retained.

## Consequences

- Consumers must tolerate duplicates and partial failure.
- The PSP contract must durably honor idempotency keys and reject reuse with
  different request data.
- A lost PSP response may cause another request, but not another logical PSP
  operation.
- "Exactly once" is claimed only for the resulting business effect, never for
  message delivery.
- Lease expiry, idempotency-key retention, and reconciliation are operationally
  monitored failure boundaries.
