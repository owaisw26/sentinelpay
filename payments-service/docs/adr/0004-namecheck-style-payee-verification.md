# ADR 0004: NameCheck-style payee verification

## Status

Accepted for the synthetic portfolio system.

## Context

SentinelPay needs to warn a customer when the name they supply does not align
with the registered owner of a receiver wallet. A fuzzy name result is not
proof of identity: unrelated people can have similar names, legitimate names
can differ, and transliteration or organizational naming conventions can lose
meaning during normalization.

Returning the registered name, per-field hints, or a similarity score would
also turn the endpoint into an information-disclosure oracle. Persisting every
supplied name would create an unnecessary store of customer-entered personal
data.

## Decision

- Maintain an internal, versioned synthetic registry keyed by receiver wallet.
  Only one version is active. A check records the version used, and a registry
  change invalidates checks made against an earlier version.
- Canonicalization applies Unicode NFKD decomposition, removes combining marks,
  uses locale-independent case folding, normalizes punctuation and whitespace,
  and removes a bounded set of terminal business suffixes.
- Canonical equality produces `MATCH`.
- A compatible token/initial structure with similarity of at least
  `0.90` produces `CLOSE_MATCH`. All other comparisons produce `NO_MATCH`.
- Return only the check ID, coarse outcome, non-revealing reason code, and
  expiry. Do not return the registered name, supplied name, component-level
  differences, or similarity score.
- Persist a SHA-256 digest of the canonical supplied name rather than the name.
- Payments require a check belonging to the initiating customer, for the same
  receiver wallet, against the active registry version, and before expiry.
- `CLOSE_MATCH` and `NO_MATCH` require explicit acceptance. An accepted
  mismatch is consumed under a database lock and cannot authorize a second
  payment. A `MATCH` may be reused until it expires.
- Include the check ID and acceptance flag in payment idempotency hashing. An
  identical payment retry replays the completed response without trying to
  consume the mismatch again.

## Consequences

This control can catch mistakes and add an explainable feature for downstream
risk decisions, but it must never be represented as confirmation that the
payee is genuine. Thresholds and suffixes are deliberately calibrated through
stable synthetic fixtures. Changes to those fixtures or rules require review
because small matching changes can alter both false-positive and false-negative
rates.

The current registry adapter derives initial synthetic entries from the local
wallet owner's synthetic name. A production implementation would replace that
adapter with an authoritative account-name source while retaining the same
versioned check contract and non-disclosure boundary.
