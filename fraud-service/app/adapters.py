from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any, Protocol
from uuid import UUID

from pydantic import ValidationError

from app.contracts import (
    PaymentScreeningEnvelopeV1,
    RiskDecisionEnvelopeV1,
    RiskEvaluationAuditV1,
)
from app.history import RiskHistory
from app.risk import RiskEvaluationPipeline


LOGGER = logging.getLogger(__name__)


class MessageContractError(ValueError):
    pass


class UnsupportedMessageVersion(MessageContractError):
    pass


class DecisionPublisher(Protocol):
    def publish(self, event: RiskDecisionEnvelopeV1) -> None: ...


class AuditSink(Protocol):
    def append(self, record: RiskEvaluationAuditV1) -> None: ...


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise MessageContractError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def _reject_json_constant(item: str) -> None:
    raise MessageContractError(f"invalid JSON constant: {item}")


def _load_json(value: str) -> Any:
    try:
        return json.loads(
            value,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_json_constant,
        )
    except json.JSONDecodeError as error:
        raise MessageContractError("message body is not valid JSON") from error


class SqsSnsEventIngestor:
    def parse(self, body: str) -> PaymentScreeningEnvelopeV1:
        document = _load_json(body)
        if not isinstance(document, dict):
            raise MessageContractError("message body must be a JSON object")

        if "Type" in document:
            if document.get("Type") != "Notification":
                raise MessageContractError("unsupported SNS message type")
            inner = document.get("Message")
            if not isinstance(inner, str):
                raise MessageContractError("SNS Message must contain JSON text")
            document = _load_json(inner)
            if not isinstance(document, dict):
                raise MessageContractError("SNS Message must be a JSON object")

        if document.get("schemaVersion") != 1:
            raise UnsupportedMessageVersion(
                f"unsupported schema version: {document.get('schemaVersion')!r}"
            )
        if document.get("eventType") != "PAYMENT_SCREENING_REQUESTED":
            raise UnsupportedMessageVersion(
                f"unsupported event type: {document.get('eventType')!r}"
            )
        try:
            # Strict mode still permits JSON's standard UUID, timestamp, enum,
            # and decimal representations while rejecting Python coercions.
            return PaymentScreeningEnvelopeV1.model_validate_json(
                json.dumps(document)
            )
        except ValidationError as error:
            raise MessageContractError("message does not match schema v1") from error


class InMemoryDecisionPublisher:
    def __init__(self) -> None:
        self.events: dict[UUID, RiskDecisionEnvelopeV1] = {}

    def publish(self, event: RiskDecisionEnvelopeV1) -> None:
        existing = self.events.get(event.event_id)
        if existing is not None and existing != event:
            raise ValueError("decision event cannot be rewritten")
        self.events[event.event_id] = event


class SnsDecisionPublisher:
    def __init__(self, sns_client: Any, topic_arn: str) -> None:
        self._sns = sns_client
        self._topic_arn = topic_arn

    def publish(self, event: RiskDecisionEnvelopeV1) -> None:
        self._sns.publish(
            TopicArn=self._topic_arn,
            Message=event.model_dump_json(by_alias=True),
            MessageAttributes={
                "eventType": {
                    "DataType": "String",
                    "StringValue": event.event_type,
                },
                "schemaVersion": {
                    "DataType": "Number",
                    "StringValue": str(event.schema_version),
                },
            },
        )


class InMemoryAuditSink:
    def __init__(self) -> None:
        self.records: dict[UUID, RiskEvaluationAuditV1] = {}

    def append(self, record: RiskEvaluationAuditV1) -> None:
        existing = self.records.get(record.decision_id)
        if existing is not None and existing != record:
            raise ValueError("audit record cannot be rewritten")
        self.records[record.decision_id] = record


class LoggingAuditSink:
    def append(self, record: RiskEvaluationAuditV1) -> None:
        LOGGER.info(
            "risk_evaluation=%s",
            record.model_dump_json(by_alias=True),
        )


class RiskMessageHandler:
    def __init__(
        self,
        ingestor: SqsSnsEventIngestor,
        history: RiskHistory,
        pipeline: RiskEvaluationPipeline,
        publisher: DecisionPublisher,
        audit_sink: AuditSink,
    ) -> None:
        self._ingestor = ingestor
        self._history = history
        self._pipeline = pipeline
        self._publisher = publisher
        self._audit_sink = audit_sink

    def handle(self, body: str) -> RiskDecisionEnvelopeV1:
        event = self._ingestor.parse(body)
        snapshot = self._history.snapshot(
            event.payload.customer_id,
            event.occurred_at,
            exclude_payment_id=event.payload.payment_id,
        )
        result = self._pipeline.evaluate(event, snapshot)

        # An input is acknowledged only after all three effects succeed. A retry
        # can republish, so the deterministic decision ID is the idempotency key.
        self._publisher.publish(result.decision_event)
        self._audit_sink.append(result.audit_record)
        self._history.record(event)
        return result.decision_event


@dataclass(frozen=True)
class PollResult:
    received: int
    acknowledged: int
    failed: int


class SqsRiskConsumer:
    def __init__(
        self,
        sqs_client: Any,
        queue_url: str,
        handler: Any,
        *,
        wait_time_seconds: int = 10,
        visibility_timeout_seconds: int = 30,
    ) -> None:
        self._sqs = sqs_client
        self._queue_url = queue_url
        self._handler = handler
        self._wait_time_seconds = wait_time_seconds
        self._visibility_timeout_seconds = visibility_timeout_seconds

    def poll_once(self) -> PollResult:
        response = self._sqs.receive_message(
            QueueUrl=self._queue_url,
            MaxNumberOfMessages=10,
            WaitTimeSeconds=self._wait_time_seconds,
            VisibilityTimeout=self._visibility_timeout_seconds,
            MessageSystemAttributeNames=["ApproximateReceiveCount"],
        )
        messages = response.get("Messages", [])
        acknowledged = 0
        failed = 0
        for message in messages:
            try:
                self._handler.handle(message["Body"])
                self._sqs.delete_message(
                    QueueUrl=self._queue_url,
                    ReceiptHandle=message["ReceiptHandle"],
                )
                acknowledged += 1
            except Exception as error:
                failed += 1
                LOGGER.warning(
                    "risk message left unacknowledged; receiveCount=%s, "
                    "failureType=%s",
                    message.get("Attributes", {}).get(
                        "ApproximateReceiveCount", "unknown"
                    ),
                    type(error).__name__,
                )
        return PollResult(len(messages), acknowledged, failed)
