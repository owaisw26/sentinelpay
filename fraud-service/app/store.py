from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

from app.contracts import (
    PaymentScreeningEnvelopeV1,
    RiskDecisionEnvelopeV1,
)
from app.history import RiskHistorySnapshot
from app.risk import EvaluationResult, RiskEvaluationPipeline


class ConflictingEventError(ValueError):
    pass


class FutureEventError(ValueError):
    pass


@dataclass(frozen=True)
class ReceivedEventResult:
    inserted: bool
    is_late: bool
    decision_due_at: datetime


@dataclass(frozen=True)
class OutboxClaim:
    event_id: UUID
    payload: str
    lease_token: UUID


class PostgresRiskStore:
    def __init__(self, connection: Any) -> None:
        self._connection = connection

    def record_received(
        self,
        event: PaymentScreeningEnvelopeV1,
        *,
        allowed_lateness: timedelta,
        maximum_future_skew: timedelta,
    ) -> ReceivedEventResult:
        from psycopg.types.json import Jsonb

        canonical = event.model_dump_json(by_alias=True)
        payload_hash = hashlib.sha256(canonical.encode()).hexdigest()
        document = json.loads(canonical)
        with self._connection.transaction():
            with self._connection.cursor() as cursor:
                # A payment is the aggregate in v1, so this transaction-scoped
                # lock serializes both exact and semantic duplicate deliveries.
                cursor.execute(
                    "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                    (str(event.payload.payment_id),),
                )
                cursor.execute(
                    """
                    SELECT event_id, aggregate_id, aggregate_sequence,
                           payment_id, envelope_sha256, is_late,
                           decision_due_at
                    FROM fraud.received_events
                    WHERE event_id = %s
                       OR (aggregate_id = %s AND event_type = %s
                           AND aggregate_sequence = %s)
                       OR payment_id = %s
                    FOR SHARE
                    """,
                    (
                        event.event_id,
                        event.aggregate_id,
                        event.event_type,
                        event.aggregate_sequence,
                        event.payload.payment_id,
                    ),
                )
                existing = cursor.fetchall()
                if existing:
                    if len(existing) != 1 or existing[0][:5] != (
                        event.event_id,
                        event.aggregate_id,
                        event.aggregate_sequence,
                        event.payload.payment_id,
                        payload_hash,
                    ):
                        raise ConflictingEventError(
                            "event identity was reused with different content"
                        )
                    return ReceivedEventResult(
                        False, existing[0][5], existing[0][6]
                    )

                cursor.execute("SELECT clock_timestamp()")
                database_now = cursor.fetchone()[0]
                if event.occurred_at > database_now + maximum_future_skew:
                    raise FutureEventError(
                        "event timestamp exceeds the permitted future skew"
                    )

                cursor.execute(
                    """
                    INSERT INTO fraud.customer_watermarks(
                        customer_id, max_occurred_at_seen, updated_at
                    ) VALUES (%s, %s, clock_timestamp())
                    ON CONFLICT (customer_id) DO UPDATE
                    SET max_occurred_at_seen = GREATEST(
                            fraud.customer_watermarks.max_occurred_at_seen,
                            EXCLUDED.max_occurred_at_seen
                        ),
                        updated_at = clock_timestamp()
                    RETURNING customer_id
                    """,
                    (event.payload.customer_id, event.occurred_at),
                )
                cursor.fetchone()

                cursor.execute("SELECT clock_timestamp()")
                received_at = cursor.fetchone()[0]
                decision_due_at = event.occurred_at + allowed_lateness
                is_late = received_at > decision_due_at
                cursor.execute(
                    """
                    INSERT INTO fraud.received_events(
                        event_id, event_type, schema_version, aggregate_id,
                        aggregate_sequence, customer_id, payment_id,
                        occurred_at, received_at, decision_due_at, is_late,
                        envelope, envelope_sha256
                    ) VALUES (
                        %s, %s, %s, %s, %s, %s, %s,
                        %s, %s, %s, %s, %s, %s
                    )
                    """,
                    (
                        event.event_id,
                        event.event_type,
                        event.schema_version,
                        event.aggregate_id,
                        event.aggregate_sequence,
                        event.payload.customer_id,
                        event.payload.payment_id,
                        event.occurred_at,
                        received_at,
                        decision_due_at,
                        is_late,
                        Jsonb(document),
                        payload_hash,
                    ),
                )
                return ReceivedEventResult(True, is_late, decision_due_at)

    def finalize_due(
        self,
        pipeline: RiskEvaluationPipeline,
        *,
        batch_size: int = 50,
    ) -> list[RiskDecisionEnvelopeV1]:
        decisions: list[RiskDecisionEnvelopeV1] = []
        with self._connection.transaction():
            with self._connection.cursor() as cursor:
                cursor.execute(
                    """
                    SELECT e.event_id, e.envelope, e.decision_due_at
                    FROM fraud.received_events e
                    LEFT JOIN fraud.risk_decisions d
                      ON d.source_event_id = e.event_id
                    WHERE d.source_event_id IS NULL
                      AND e.decision_due_at <= clock_timestamp()
                    ORDER BY e.decision_due_at, e.event_id
                    LIMIT %s
                    """,
                    (batch_size,),
                )
                pending = cursor.fetchall()
                for event_id, document, decision_due_at in pending:
                    cursor.execute(
                        "SELECT pg_try_advisory_xact_lock("
                        "hashtextextended(%s, 1))",
                        (str(event_id),),
                    )
                    if not cursor.fetchone()[0]:
                        continue
                    cursor.execute(
                        "SELECT EXISTS (SELECT 1 FROM fraud.risk_decisions "
                        "WHERE source_event_id = %s)",
                        (event_id,),
                    )
                    if cursor.fetchone()[0]:
                        continue
                    event = PaymentScreeningEnvelopeV1.model_validate_json(
                        json.dumps(document)
                    )
                    # Ingestion locks this row before assigning received_at.
                    # Once held here, no event for this customer can cross the
                    # deadline while its feature snapshot is being finalized.
                    cursor.execute(
                        """
                        SELECT customer_id
                        FROM fraud.customer_watermarks
                        WHERE customer_id = %s
                        FOR UPDATE
                        """,
                        (event.payload.customer_id,),
                    )
                    cursor.fetchone()
                    snapshot = self._snapshot_at_deadline(
                        cursor, event, decision_due_at
                    )
                    result = pipeline.evaluate(event, snapshot)
                    self._persist_decision(cursor, event, result)
                    decisions.append(result.decision_event)
        return decisions

    def claim_outbox(self, *, batch_size: int = 50) -> list[OutboxClaim]:
        claims: list[OutboxClaim] = []
        with self._connection.transaction():
            with self._connection.cursor() as cursor:
                cursor.execute(
                    """
                    SELECT event_id, payload
                    FROM fraud.decision_outbox
                    WHERE published_at IS NULL
                      AND next_attempt_at <= clock_timestamp()
                      AND (lease_until IS NULL
                           OR lease_until <= clock_timestamp())
                    ORDER BY created_at, event_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT %s
                    """,
                    (batch_size,),
                )
                for event_id, payload in cursor.fetchall():
                    lease_token = uuid4()
                    cursor.execute(
                        """
                        UPDATE fraud.decision_outbox
                        SET lease_token = %s,
                            lease_until = clock_timestamp() + interval '30 seconds',
                            attempt_count = attempt_count + 1,
                            last_error = NULL
                        WHERE event_id = %s
                        """,
                        (lease_token, event_id),
                    )
                    claims.append(
                        OutboxClaim(
                            event_id,
                            json.dumps(payload, separators=(",", ":")),
                            lease_token,
                        )
                    )
        return claims

    def complete_outbox(self, claim: OutboxClaim) -> None:
        with self._connection.transaction():
            with self._connection.cursor() as cursor:
                cursor.execute(
                    """
                    UPDATE fraud.decision_outbox
                    SET published_at = clock_timestamp(), lease_token = NULL,
                        lease_until = NULL, last_error = NULL
                    WHERE event_id = %s AND lease_token = %s
                      AND published_at IS NULL
                    """,
                    (claim.event_id, claim.lease_token),
                )
                if cursor.rowcount != 1:
                    raise RuntimeError("decision outbox lease was lost")

    def fail_outbox(self, claim: OutboxClaim, error: Exception) -> None:
        message = str(error)[:1000] or type(error).__name__
        with self._connection.transaction():
            with self._connection.cursor() as cursor:
                cursor.execute(
                    """
                    UPDATE fraud.decision_outbox
                    SET lease_token = NULL, lease_until = NULL,
                        next_attempt_at = clock_timestamp() + interval '1 second',
                        last_error = %s
                    WHERE event_id = %s AND lease_token = %s
                      AND published_at IS NULL
                    """,
                    (message, claim.event_id, claim.lease_token),
                )

    def _snapshot_at_deadline(
        self,
        cursor: Any,
        event: PaymentScreeningEnvelopeV1,
        decision_due_at: datetime,
    ) -> RiskHistorySnapshot:
        cursor.execute(
            """
            SELECT payment_id, customer_id, envelope, occurred_at
            FROM fraud.received_events
            WHERE customer_id = %s
              AND payment_id <> %s
              AND occurred_at <= %s
              AND received_at <= %s
            ORDER BY occurred_at, event_id
            """,
            (
                event.payload.customer_id,
                event.payload.payment_id,
                event.occurred_at,
                decision_due_at,
            ),
        )
        observed = cursor.fetchall()
        known_payees: set[UUID] = set()
        last_device: str | None = None
        count = 0
        window_start = event.occurred_at - timedelta(minutes=10)
        for _payment_id, _customer_id, document, occurred_at in observed:
            prior = PaymentScreeningEnvelopeV1.model_validate_json(
                json.dumps(document)
            )
            known_payees.add(prior.payload.payee_id)
            last_device = prior.payload.device_token
            if window_start <= occurred_at <= event.occurred_at:
                count += 1
        return RiskHistorySnapshot(
            known_payee_ids=frozenset(known_payees),
            last_device_token=last_device,
            transaction_count_10_minutes=count,
        )

    def _persist_decision(
        self,
        cursor: Any,
        event: PaymentScreeningEnvelopeV1,
        result: EvaluationResult,
    ) -> None:
        from psycopg.types.json import Jsonb

        decision = result.decision_event
        payload = decision.payload
        cursor.execute(
            """
            INSERT INTO fraud.risk_features(
                decision_id, source_event_id, feature_version,
                features, computed_at
            ) VALUES (%s, %s, %s, %s, clock_timestamp())
            ON CONFLICT (source_event_id) DO NOTHING
            """,
            (
                payload.decision_id,
                event.event_id,
                payload.feature_version,
                Jsonb(result.audit_record.features.model_dump(
                    mode="json", by_alias=True
                )),
            ),
        )
        decision_document = json.loads(
            decision.model_dump_json(by_alias=True)
        )
        cursor.execute(
            """
            INSERT INTO fraud.risk_decisions(
                decision_id, payment_id, source_event_id,
                source_aggregate_sequence, feature_version, ruleset_version,
                model_version, deterministic_score, anomaly_score,
                anomaly_contribution, score, action, reason_codes, decision,
                decided_at
            ) VALUES (
                %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
                %s, %s, clock_timestamp()
            )
            ON CONFLICT (source_event_id) DO NOTHING
            """,
            (
                payload.decision_id,
                payload.payment_id,
                payload.source_event_id,
                payload.source_aggregate_sequence,
                payload.feature_version,
                payload.ruleset_version,
                payload.model_version,
                result.audit_record.deterministic_score,
                result.audit_record.anomaly_score,
                result.audit_record.anomaly_contribution,
                payload.score,
                payload.action.value,
                list(payload.reason_codes),
                Jsonb(decision_document),
            ),
        )
        cursor.execute(
            """
            INSERT INTO fraud.decision_outbox(
                event_id, payload, created_at, next_attempt_at
            ) VALUES (%s, %s, clock_timestamp(), clock_timestamp())
            ON CONFLICT (event_id) DO NOTHING
            """,
            (decision.event_id, Jsonb(decision_document)),
        )
        cursor.execute(
            """
            UPDATE fraud.customer_watermarks
            SET finalized_through = GREATEST(
                    COALESCE(finalized_through, %s), %s
                ),
                updated_at = clock_timestamp()
            WHERE customer_id = %s
            """,
            (event.occurred_at, event.occurred_at, event.payload.customer_id),
        )


class DurableRiskMessageHandler:
    def __init__(
        self,
        ingestor: Any,
        store: PostgresRiskStore,
        *,
        allowed_lateness: timedelta,
        maximum_future_skew: timedelta,
    ) -> None:
        self._ingestor = ingestor
        self._store = store
        self._allowed_lateness = allowed_lateness
        self._maximum_future_skew = maximum_future_skew

    def handle(self, body: str) -> ReceivedEventResult:
        return self._store.record_received(
            self._ingestor.parse(body),
            allowed_lateness=self._allowed_lateness,
            maximum_future_skew=self._maximum_future_skew,
        )


class PostgresDecisionOutboxPublisher:
    def __init__(self, store: PostgresRiskStore, sns: Any, topic_arn: str) -> None:
        self._store = store
        self._sns = sns
        self._topic_arn = topic_arn

    def publish_batch(self, *, batch_size: int = 50) -> int:
        published = 0
        for claim in self._store.claim_outbox(batch_size=batch_size):
            try:
                self._sns.publish(
                    TopicArn=self._topic_arn,
                    Message=claim.payload,
                    MessageAttributes={
                        "eventType": {
                            "DataType": "String",
                            "StringValue": "RISK_DECISION_MADE",
                        },
                        "schemaVersion": {
                            "DataType": "Number",
                            "StringValue": "1",
                        },
                    },
                )
                self._store.complete_outbox(claim)
                published += 1
            except Exception as error:
                self._store.fail_outbox(claim, error)
        return published


def apply_migrations(connection: Any) -> None:
    migration_directory = Path(__file__).resolve().parent.parent / "migrations"
    migrations = sorted(
        migration_directory.glob("V*.sql"),
        key=lambda path: int(path.name.split("__", 1)[0][1:]),
    )
    for migration in migrations:
        with connection.transaction():
            with connection.cursor() as cursor:
                cursor.execute(migration.read_text())
