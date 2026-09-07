# ADR 0003: Detect Automatically and Repair with Analyst Approval

- Status: Accepted
- Date: 2026-09-06

## Context

SentinelPay can miss a final PSP webhook or receive a final provider result that
contradicts local state. Detection must not silently become permission to move
money. A faulty provider integration could otherwise amplify one bad response
into many incorrect captures or releases.

Two designs were considered:

1. Detect discrepancies automatically and require an analyst to initiate every
   money-changing repair.
2. Automatically capture or release when a provider lookup returns a terminal
   result, leaving only ambiguous cases for analysts.

## Decision

SentinelPay detects discrepancies automatically but requires analyst approval
for every repair. Resolution re-fetches provider state, locks the discrepancy,
payment, wallets, and reservation before applying an action, and derives the
permitted action from current provider state. Analysts provide an expected
version, reason, and idempotency key, but never an amount or wallet identifier.

The invariants are:

- Detection never changes a payment, reservation, wallet, or ledger entry.
- `SUCCEEDED` can permit only `CAPTURE`; `DECLINED` can permit only `RELEASE`.
- A resolution is applied at most once and every attempt is durably audited.
- Existing reservation and unique payment-settlement constraints remain the
  final protection against duplicate financial effects.
- A concurrent webhook either wins the payment lock or observes the analyst's
  terminal result; it cannot cause a second capture or release.

## Consequences

Confirmed external outcomes can remain unresolved until an analyst acts, so
reservation age and open discrepancy age require operational monitoring. The
manual checkpoint limits the blast radius of incorrect provider status data and
produces an explicit reason and actor for every repair.

Guarded automatic repair was rejected for this version because a provider or
adapter defect could turn a detection batch into a high-volume money movement
incident before an operator can intervene.
