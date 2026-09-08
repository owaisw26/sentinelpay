import json
from datetime import datetime, timezone
from decimal import Decimal
from uuid import UUID, uuid4

from app.contracts import NameCheckOutcome, PaymentScreeningEnvelopeV1


DEFAULT_CUSTOMER_ID = UUID("10000000-0000-0000-0000-000000000001")
DEFAULT_PAYEE_ID = UUID("20000000-0000-0000-0000-000000000001")
DEFAULT_DEVICE_TOKEN = "device_token_0001"


def screening_event(
    *,
    event_id=None,
    payment_id=None,
    customer_id=DEFAULT_CUSTOMER_ID,
    payee_id=DEFAULT_PAYEE_ID,
    amount=Decimal("100.00"),
    device_token=DEFAULT_DEVICE_TOKEN,
    name_check_outcome="MATCH",
    accepted_name_mismatch=False,
    occurred_at=datetime(2026, 9, 8, 1, 0, tzinfo=timezone.utc),
) -> PaymentScreeningEnvelopeV1:
    payment_id = payment_id or uuid4()
    return PaymentScreeningEnvelopeV1.model_validate(
        {
            "eventId": event_id or uuid4(),
            "eventType": "PAYMENT_SCREENING_REQUESTED",
            "schemaVersion": 1,
            "aggregateId": payment_id,
            "aggregateSequence": 1,
            "occurredAt": occurred_at,
            "correlationId": uuid4(),
            "causationId": None,
            "payload": {
                "paymentId": payment_id,
                "customerId": customer_id,
                "payeeId": payee_id,
                "amount": amount,
                "currency": "AUD",
                "deviceToken": device_token,
                "nameCheckOutcome": NameCheckOutcome(name_check_outcome),
                "acceptedNameMismatch": accepted_name_mismatch,
            },
        }
    )


def event_body(event: PaymentScreeningEnvelopeV1) -> str:
    return event.model_dump_json(by_alias=True)


def sns_body(event: PaymentScreeningEnvelopeV1) -> str:
    return json.dumps({"Type": "Notification", "Message": event_body(event)})
