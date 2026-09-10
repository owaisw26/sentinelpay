
Don't invent connections we haven't built yet.

### `docs/api.md`

Use the API you designed during system design:

```markdown
# Initial API Contract

## Create Transfer

`POST /transfers`

Headers:

- `Authorization`
- `Idempotency-Key`

Example request:

```json
{
  "receiverAccountId": "acc_456",
  "amount": 100.00,
  "currency": "AUD"
}

Example response:
```json
{
  "transferId": "tr_123",
  "status": "PROCESSING",
  "receiverAccountId": "acc_456",
  "amount": 100.00,
  "currency": "AUD"
}
