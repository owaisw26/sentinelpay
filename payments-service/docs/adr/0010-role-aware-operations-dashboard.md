# ADR 0010: Role-aware operations dashboard

## Status

Accepted for Day 16.

## Context

SentinelPay needs one browser experience for customer payment initiation and
analyst operations without treating frontend routing as a security boundary.
The dashboard must support local development before Terraform provisions
Cognito, while retaining the same bearer-token API contract in both modes.
Analysts also need to review held payments using the risk explanation that
caused the hold and make a single auditable decision.

## Considered alternatives

1. One role-aware SPA. Customer and analyst route trees share a deployment and
   authentication boundary. Analyst code is loaded only when an analyst route
   is visited. Java independently authorizes every request.
2. Separate customer and analyst SPAs. Separate deployments and Cognito app
   clients reduce the frontend compromise boundary, but duplicate build,
   configuration, and release machinery. They do not remove the requirement
   for server-side authorization.

For authentication, we considered browser-owned Authorization Code with PKCE
and a backend-for-frontend session. The BFF keeps OAuth tokens away from
JavaScript but adds server sessions, cookies, and CSRF controls.

For audit presentation, we considered object-specific trails and a unified
cross-service analyst feed. A unified feed would require aggregation across the
payments and fraud service data stores.

## Decision

Use one role-aware React SPA with separately lazy-loaded customer and analyst
route trees. React may use verified token claims to choose navigation, but it
never grants authority. Spring validates the token and applies analyst-role,
customer-ownership, and payment-state checks for every operation.

Use Cognito Authorization Code with PKCE for cloud builds. The SPA is a public
client with no client secret. OAuth interaction state may live briefly in
session storage, while access, ID, and refresh tokens remain in memory. The API
accepts only an access token and validates its signature, issuer, expiry,
`token_use`, app-client identifier, API audience, and groups. Local builds use
the local-only token issuer through the same in-memory authentication session.

Map the external Cognito subject to a unique, internal SentinelPay UUID. Never
assume an identity-provider subject is an application UUID.

Held-payment review is a state-changing approval station. An analyst supplies
an expected payment version, `APPROVE` or `BLOCK`, a reason, and an idempotency
key. Approval locks the payment, reserves funds, transitions it to processing,
and emits provider work in one transaction. Blocking transitions it once
without provider work. Both outcomes are append-only audited.

Present object-specific audit trails for held payments, reconciliation, and
rule proposals. Defer a unified cross-service audit feed.

## Consequences

- One frontend remains simple to build and demonstrate while route-level code
  splitting avoids shipping analyst functionality in the customer entry path.
- A customer can still discover analyst API paths, so missing Java
  authorization remains a security defect and is integration-tested.
- In-memory tokens reduce persistence after an XSS incident, but a page reload
  requires sign-in recovery and the browser still needs strong CSP and
  dependency controls.
- Held approvals share the same reservation and outbox invariants as automated
  approvals.
- A production bank could later split deployments, require analyst MFA and
  managed devices, add dual control, and project domain audits into a dedicated
  security audit store.
