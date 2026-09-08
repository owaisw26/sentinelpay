import json

from app.adapters import (
    InMemoryAuditSink,
    InMemoryDecisionPublisher,
    RiskMessageHandler,
    SnsDecisionPublisher,
    SqsRiskConsumer,
    SqsSnsEventIngestor,
)
from app.history import InMemoryRiskHistory
from app.risk import RiskEvaluationPipeline
from tests.factories import event_body, screening_event


class FakeSqs:
    def __init__(self, messages):
        self.messages = messages
        self.deleted = []

    def receive_message(self, **_kwargs):
        return {"Messages": self.messages}

    def delete_message(self, **kwargs):
        self.deleted.append(kwargs["ReceiptHandle"])


class FakeSns:
    def __init__(self):
        self.published = []

    def publish(self, **kwargs):
        self.published.append(kwargs)


def _handler():
    return RiskMessageHandler(
        SqsSnsEventIngestor(),
        InMemoryRiskHistory(),
        RiskEvaluationPipeline(),
        InMemoryDecisionPublisher(),
        InMemoryAuditSink(),
    )


def test_malformed_and_version_incompatible_messages_are_not_acknowledged():
    valid = json.loads(event_body(screening_event()))
    incompatible = {**valid, "schemaVersion": 2}
    sqs = FakeSqs(
        [
            {"Body": "not-json", "ReceiptHandle": "malformed"},
            {
                "Body": json.dumps(incompatible),
                "ReceiptHandle": "incompatible",
            },
            {"Body": json.dumps(valid), "ReceiptHandle": "valid"},
        ]
    )

    result = SqsRiskConsumer(sqs, "queue-url", _handler()).poll_once()

    assert result.received == 3
    assert result.acknowledged == 1
    assert result.failed == 2
    assert sqs.deleted == ["valid"]


def test_failure_after_decision_prevents_acknowledgement():
    class FailingAudit:
        def append(self, _record):
            raise RuntimeError("audit unavailable")

    publisher = InMemoryDecisionPublisher()
    handler = RiskMessageHandler(
        SqsSnsEventIngestor(),
        InMemoryRiskHistory(),
        RiskEvaluationPipeline(),
        publisher,
        FailingAudit(),
    )
    sqs = FakeSqs(
        [{"Body": event_body(screening_event()), "ReceiptHandle": "retry"}]
    )

    result = SqsRiskConsumer(sqs, "queue-url", handler).poll_once()

    assert result.acknowledged == 0
    assert result.failed == 1
    assert sqs.deleted == []
    assert len(publisher.events) == 1


def test_duplicate_delivery_reuses_decision_and_audit_identity():
    publisher = InMemoryDecisionPublisher()
    audit = InMemoryAuditSink()
    handler = RiskMessageHandler(
        SqsSnsEventIngestor(),
        InMemoryRiskHistory(),
        RiskEvaluationPipeline(),
        publisher,
        audit,
    )
    body = event_body(screening_event())

    first = handler.handle(body)
    retried = handler.handle(body)

    assert retried == first
    assert len(publisher.events) == 1
    assert len(audit.records) == 1


def test_sns_publisher_emits_versioned_decision_envelope():
    sns = FakeSns()
    event = screening_event()
    history = InMemoryRiskHistory()
    result = RiskEvaluationPipeline().evaluate(
        event,
        history.snapshot(event.payload.customer_id, event.occurred_at),
    )

    SnsDecisionPublisher(sns, "topic-arn").publish(result.decision_event)

    published = sns.published[0]
    body = json.loads(published["Message"])
    assert published["TopicArn"] == "topic-arn"
    assert body["eventType"] == "RISK_DECISION_MADE"
    assert body["schemaVersion"] == 1
    assert body["payload"]["sourceEventId"] == str(event.event_id)
