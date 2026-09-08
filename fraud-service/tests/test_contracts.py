import json

import pytest

from app.adapters import (
    MessageContractError,
    SqsSnsEventIngestor,
    UnsupportedMessageVersion,
)
from tests.factories import event_body, screening_event, sns_body


def test_ingests_direct_sqs_and_sns_wrapped_messages():
    ingestor = SqsSnsEventIngestor()
    event = screening_event()

    assert ingestor.parse(event_body(event)) == event
    assert ingestor.parse(sns_body(event)) == event


def test_rejects_unknown_fields_and_invalid_namecheck_combination():
    ingestor = SqsSnsEventIngestor()
    document = json.loads(event_body(screening_event()))
    document["payload"]["unexpected"] = True

    with pytest.raises(MessageContractError):
        ingestor.parse(json.dumps(document))

    document.pop("payload")
    document["payload"] = json.loads(event_body(screening_event()))["payload"]
    document["payload"]["nameCheckOutcome"] = "NO_MATCH"
    with pytest.raises(MessageContractError):
        ingestor.parse(json.dumps(document))


def test_rejects_unknown_schema_version_before_payload_validation():
    ingestor = SqsSnsEventIngestor()
    document = json.loads(event_body(screening_event()))
    document["schemaVersion"] = 2

    with pytest.raises(UnsupportedMessageVersion):
        ingestor.parse(json.dumps(document))


def test_rejects_naive_timestamp_and_non_aud_currency():
    ingestor = SqsSnsEventIngestor()
    document = json.loads(event_body(screening_event()))
    document["occurredAt"] = "2026-09-08T01:00:00"
    with pytest.raises(MessageContractError):
        ingestor.parse(json.dumps(document))

    document = json.loads(event_body(screening_event()))
    document["payload"]["currency"] = "USD"
    with pytest.raises(MessageContractError):
        ingestor.parse(json.dumps(document))


def test_rejects_duplicate_json_fields():
    body = event_body(screening_event())
    duplicated = body.replace(
        '"schemaVersion":1',
        '"schemaVersion":1,"schemaVersion":1',
    )

    with pytest.raises(MessageContractError):
        SqsSnsEventIngestor().parse(duplicated)
