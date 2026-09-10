# ADR 0002: Reserve Funds Before PSP Processing

- Status: Accepted
- Date: 2026-09-06

## Context

SentinelPay sends an asynchronous payment request to an external PSP. The PSP
may accept the operation before SentinelPay learns the final result, and the
same request or webhook may be delivered more than once. During that interval,
the customer must not be able to spend the same money through another payment.

Two designs were considered:

1. An authorization reservation keeps posted balance unchanged and records the
   amount as unavailable until the payment is captured or released.
2. Ledger escrow immediately posts funds into an internal escrow wallet, then
   posts either settlement or reversal entries after the PSP result.

## Decision

Use authorization reservations. The payment-creation transaction locks the
wallets and creates the reservation before the payment enters risk screening.
An amount above the sender's available balance is rejected immediately and no
payment or screening event is committed. `wallet.balance` remains posted
balance and `wallet.reserved_balance` is the total amount unavailable to new
payments. Each payment has at most one durable reservation with an `ACTIVE`,
`CAPTURED`, or `RELEASED` status.

The reservation remains active while a payment is screening or held for analyst
review. Approval reuses the existing reservation, settlement captures it, and
blocking or provider failure releases it.

The invariants are:

- `available_balance = balance - reserved_balance`.
- `0 <= reserved_balance <= balance`.
- A wallet's `reserved_balance` equals the sum of its `ACTIVE` reservations.
- Capture changes the reservation, both wallets, the ledger settlement, and
  the payment state in one database transaction.
- Release changes the reservation, wallet, and payment state in one database
  transaction.
- A payment can produce at most one settlement ledger transaction.

Wallet rows are locked in UUID order. The payment and reservation are locked
before terminal state changes. These locks serialize concurrent reservations
and duplicate terminal events.

## Consequences

The customer sees a distinction between posted and available balance. No
ledger entries are written until a provider capture is confirmed, which keeps
the ledger aligned with completed financial effects. Operational monitoring
must detect stale active reservations and any mismatch between wallet totals
and reservation rows.

Ledger escrow was rejected for this version because PSP acceptance is not a
completed transfer. Escrow would add internal-wallet accounting, reversal
entries, and reconciliation paths without improving the chosen customer money
semantics.
