from __future__ import annotations

from datetime import datetime, timedelta, timezone

import psycopg
import pytest
from testcontainers.community.postgres import PostgresContainer

from app.adapters import SqsSnsEventIngestor
from app.risk import RiskEvaluationPipeline
from app.store import (
    ConflictingEventError,
    DurableRiskMessageHandler,
    FutureEventError,
    PostgresDecisionOutboxPublisher,
    PostgresRiskStore,
    apply_migrations,
)
from tests.factories import event_body, screening_event


@pytest.fixture(scope="module")
def database():
    with PostgresContainer("postgres:17-alpine") as postgres:
        dsn = postgres.get_connection_url().replace(
            "postgresql+psycopg2", "postgresql"
        )
        connection = psycopg.connect(dsn, autocommit=True)
        apply_migrations(connection)
        apply_migrations(connection)
        yield connection
        connection.close()


def _value(connection, query, parameters=()):
    with connection.cursor() as cursor:
        cursor.execute(query, parameters)
        return cursor.fetchone()[0]


@pytest.mark.postgres
def test_duplicate_delivery_and_finalize_are_database_no_ops(database):
    store = PostgresRiskStore(database)
    event = screening_event(
        occurred_at=datetime.now(timezone.utc) - timedelta(minutes=1)
    )
    handler = DurableRiskMessageHandler(
        SqsSnsEventIngestor(),
        store,
        allowed_lateness=timedelta(seconds=30),
        maximum_future_skew=timedelta(seconds=30),
    )

    first = handler.handle(event_body(event))
    watermark_after_first = _value(
        database,
        "SELECT updated_at FROM fraud.customer_watermarks WHERE customer_id = %s",
        (event.payload.customer_id,),
    )
    replay = handler.handle(event_body(event))
    watermark_after_replay = _value(
        database,
        "SELECT updated_at FROM fraud.customer_watermarks WHERE customer_id = %s",
        (event.payload.customer_id,),
    )
    first_decisions = store.finalize_due(RiskEvaluationPipeline())
    replay_decisions = store.finalize_due(RiskEvaluationPipeline())

    assert first.inserted is True
    assert replay.inserted is False
    assert watermark_after_replay == watermark_after_first
    assert len(first_decisions) == 1
    assert replay_decisions == []
    assert _value(
        database,
        "SELECT count(*) FROM fraud.received_events WHERE event_id = %s",
        (event.event_id,),
    ) == 1
    assert _value(
        database,
        "SELECT count(*) FROM fraud.risk_decisions WHERE source_event_id = %s",
        (event.event_id,),
    ) == 1
    assert _value(
        database,
        "SELECT count(*) FROM fraud.risk_features WHERE source_event_id = %s",
        (event.event_id,),
    ) == 1
    assert _value(
        database,
        "SELECT deterministic_score = score "
        "AND anomaly_score IS NULL "
        "AND anomaly_contribution = 0 "
        "FROM fraud.risk_decisions WHERE source_event_id = %s",
        (event.event_id,),
    ) is True


@pytest.mark.postgres
def test_conflicting_semantic_duplicate_is_rejected(database):
    store = PostgresRiskStore(database)
    original = screening_event(
        occurred_at=datetime.now(timezone.utc) - timedelta(minutes=1)
    )
    conflict = screening_event(
        payment_id=original.payload.payment_id,
        amount=original.payload.amount + 1,
        occurred_at=original.occurred_at,
    )
    store.record_received(
        original,
        allowed_lateness=timedelta(seconds=30),
        maximum_future_skew=timedelta(seconds=30),
    )

    with pytest.raises(ConflictingEventError):
        store.record_received(
            conflict,
            allowed_lateness=timedelta(seconds=30),
            maximum_future_skew=timedelta(seconds=30),
        )


@pytest.mark.postgres
def test_excessive_future_timestamp_is_rejected_before_persistence(database):
    store = PostgresRiskStore(database)
    event = screening_event(
        occurred_at=datetime.now(timezone.utc) + timedelta(minutes=2)
    )

    with pytest.raises(FutureEventError):
        store.record_received(
            event,
            allowed_lateness=timedelta(seconds=30),
            maximum_future_skew=timedelta(seconds=30),
        )

    assert _value(
        database,
        "SELECT count(*) FROM fraud.received_events WHERE event_id = %s",
        (event.event_id,),
    ) == 0


@pytest.mark.postgres
def test_late_event_is_retained_without_rewriting_final_decision(database):
    store = PostgresRiskStore(database)
    now = datetime.now(timezone.utc)
    later = screening_event(occurred_at=now - timedelta(minutes=2))
    store.record_received(
        later,
        allowed_lateness=timedelta(seconds=30),
        maximum_future_skew=timedelta(seconds=30),
    )
    original_decision = store.finalize_due(RiskEvaluationPipeline())[0]

    earlier = screening_event(
        customer_id=later.payload.customer_id,
        occurred_at=now - timedelta(minutes=3),
    )
    late_result = store.record_received(
        earlier,
        allowed_lateness=timedelta(seconds=30),
        maximum_future_skew=timedelta(seconds=30),
    )
    store.finalize_due(RiskEvaluationPipeline())

    stored_decision = _value(
        database,
        "SELECT decision FROM fraud.risk_decisions WHERE decision_id = %s",
        (original_decision.event_id,),
    )
    assert late_result.is_late is True
    assert stored_decision["eventId"] == str(original_decision.event_id)
    assert _value(
        database,
        "SELECT count(*) FROM fraud.risk_decisions WHERE source_event_id = %s",
        (later.event_id,),
    ) == 1


@pytest.mark.postgres
def test_decision_outbox_retries_with_stable_event_identity(database):
    class FakeSns:
        def __init__(self):
            self.messages = []

        def publish(self, **kwargs):
            self.messages.append(kwargs)

    store = PostgresRiskStore(database)
    event = screening_event(
        occurred_at=datetime.now(timezone.utc) - timedelta(minutes=1)
    )
    store.record_received(
        event,
        allowed_lateness=timedelta(seconds=30),
        maximum_future_skew=timedelta(seconds=30),
    )
    decision = store.finalize_due(RiskEvaluationPipeline())[0]
    sns = FakeSns()

    assert PostgresDecisionOutboxPublisher(
        store, sns, "topic-arn"
    ).publish_batch() >= 1
    matching = [
        item for item in sns.messages
        if str(decision.event_id) in item["Message"]
    ]
    assert len(matching) == 1
    assert _value(
        database,
        "SELECT published_at IS NOT NULL FROM fraud.decision_outbox "
        "WHERE event_id = %s",
        (decision.event_id,),
    ) is True


@pytest.mark.postgres
def test_event_decision_and_features_are_append_only(database):
    event = screening_event(
        occurred_at=datetime.now(timezone.utc) - timedelta(minutes=1)
    )
    store = PostgresRiskStore(database)
    store.record_received(
        event,
        allowed_lateness=timedelta(seconds=30),
        maximum_future_skew=timedelta(seconds=30),
    )
    store.finalize_due(RiskEvaluationPipeline())

    with pytest.raises(psycopg.errors.RaiseException):
        with database.transaction():
            with database.cursor() as cursor:
                cursor.execute(
                    "UPDATE fraud.received_events SET is_late = false "
                    "WHERE event_id = %s",
                    (event.event_id,),
                )


@pytest.mark.postgres
def test_fraud_role_has_only_runtime_schema_privileges(database):
    assert _value(
        database,
        "SELECT has_schema_privilege('fraud_app', 'fraud', 'USAGE')",
    ) is True
    assert _value(
        database,
        "SELECT has_schema_privilege('fraud_app', 'fraud', 'CREATE')",
    ) is False
    assert _value(
        database,
        "SELECT has_table_privilege("
        "'fraud_app', 'fraud.received_events', 'INSERT')",
    ) is True
    assert _value(
        database,
        "SELECT has_table_privilege("
        "'fraud_app', 'fraud.received_events', 'DELETE')",
    ) is False
    assert _value(
        database,
        "SELECT has_table_privilege("
        "'fraud_app', 'fraud.decision_outbox', 'UPDATE')",
    ) is True
