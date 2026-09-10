# Day 16 operations dashboard

## Local authentication

The local profile creates three deterministic demo personas and two wallets:

- Avery Customer — funded customer wallet
- Morgan Lee — receiver wallet and synthetic payee-registry name
- Riley Analyst — analyst approval station

`GET /dev/personas` and `POST /dev/token/{userId}` exist only in the `local`
profile. The dashboard keeps the returned bearer token in React memory.
The local profile also uses the fake PSP's `AUTO_SUCCESS` mode, which delivers
one successful webhook after provider acceptance so normal demo payments reach
`SETTLED`. Other fake PSP modes remain available for failure and reconciliation
scenarios.

## Customer APIs

- `GET /wallets` lists only the authenticated customer's wallets.
- `GET /payments?cursor=&limit=` lists only payments initiated by the
  authenticated customer using an opaque keyset cursor.
- Existing `POST /payee-checks`, `POST /payments`, and `GET /payments/{id}`
  power verification, mismatch confirmation, initiation, and status polling.

## Analyst held-payment APIs

All routes require the `ANALYST` authority.

- `GET /analyst/held-payments?cursor=&limit=` returns safe risk explanations
  without PSP identifiers, payee names, raw events, or device information.
- `GET /analyst/held-payments/{paymentId}` reads one currently held payment.
- `POST /analyst/held-payments/{paymentId}/decision` requires an
  `Idempotency-Key` header and the body below.
- `GET /analyst/held-payments/{paymentId}/audit` returns its append-only trail.

```json
{
  "expectedVersion": 3,
  "action": "APPROVE",
  "reason": "Reviewed the risk explanation and confirmed the payment"
}
```

`APPROVE` reserves funds and queues provider processing atomically. `BLOCK`
never calls the provider. A stale version, reused key with different input, or
decision against a payment that is no longer held returns RFC 7807 `409`.

## Cognito build configuration

Set `VITE_AUTH_MODE=cognito` plus the authority, public app-client ID, API
audience, and scopes documented in `dashboard/.env.example`. The matching Java
cloud profile requires `COGNITO_ISSUER_URI`, `COGNITO_APP_CLIENT_ID`, and
`COGNITO_API_AUDIENCE`.
