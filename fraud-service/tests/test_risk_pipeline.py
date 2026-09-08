from datetime import timedelta
from decimal import Decimal
from uuid import uuid4

import pytest

from app.contracts import RiskAction
from app.history import InMemoryRiskHistory
from app.risk import DecisionClassifier, RiskEvaluationPipeline
from tests.factories import DEFAULT_PAYEE_ID, screening_event


def _record_prior(
    history,
    current,
    *,
    minutes_ago,
    payee_id=DEFAULT_PAYEE_ID,
    device_token="device_token_0001",
):
    history.record(
        screening_event(
            payment_id=uuid4(),
            customer_id=current.payload.customer_id,
            payee_id=payee_id,
            device_token=device_token,
            occurred_at=current.occurred_at - timedelta(minutes=minutes_ago),
        )
    )


def test_seeded_approve_scenario_is_deterministic():
    event = screening_event()
    history = InMemoryRiskHistory()
    _record_prior(history, event, minutes_ago=20)
    snapshot = history.snapshot(event.payload.customer_id, event.occurred_at)
    pipeline = RiskEvaluationPipeline()

    first = pipeline.evaluate(event, snapshot)
    second = pipeline.evaluate(event, snapshot)

    assert first == second
    assert first.decision_event.payload.action is RiskAction.APPROVE
    assert first.decision_event.payload.score == 0
    assert first.decision_event.payload.reason_codes == ()


def test_seeded_hold_has_individual_reason_codes():
    event = screening_event(amount=Decimal("5000.00"), payee_id=uuid4())
    history = InMemoryRiskHistory()
    _record_prior(history, event, minutes_ago=20)

    result = RiskEvaluationPipeline().evaluate(
        event,
        history.snapshot(event.payload.customer_id, event.occurred_at),
    )

    assert result.decision_event.payload.action is RiskAction.HOLD
    assert result.decision_event.payload.score == 45
    assert result.decision_event.payload.reason_codes == (
        "AMOUNT_GTE_AUD_5000",
        "FIRST_TIME_PAYEE",
    )


def test_seeded_block_counts_current_payment_in_velocity_window():
    event = screening_event(
        name_check_outcome="NO_MATCH",
        accepted_name_mismatch=True,
    )
    history = InMemoryRiskHistory()
    for minutes_ago in (9, 7, 5, 1):
        _record_prior(history, event, minutes_ago=minutes_ago)

    result = RiskEvaluationPipeline().evaluate(
        event,
        history.snapshot(event.payload.customer_id, event.occurred_at),
    )

    assert result.audit_record.features.transaction_count_10_minutes == 5
    assert result.decision_event.payload.action is RiskAction.BLOCK
    assert result.decision_event.payload.score == 75
    assert result.decision_event.payload.reason_codes == (
        "FIVE_TRANSACTIONS_10_MINUTES",
        "NAMECHECK_MISMATCH_ACCEPTED",
    )


def test_all_five_rules_contribute_in_stable_order():
    event = screening_event(
        amount=Decimal("5000.00"),
        payee_id=uuid4(),
        device_token="changed_device_001",
        name_check_outcome="CLOSE_MATCH",
        accepted_name_mismatch=True,
    )
    history = InMemoryRiskHistory()
    for minutes_ago in (9, 7, 5, 1):
        _record_prior(
            history,
            event,
            minutes_ago=minutes_ago,
            device_token="original_device_1",
        )

    result = RiskEvaluationPipeline().evaluate(
        event,
        history.snapshot(event.payload.customer_id, event.occurred_at),
    )

    assert result.decision_event.payload.score == 140
    assert result.decision_event.payload.reason_codes == (
        "AMOUNT_GTE_AUD_5000",
        "FIRST_TIME_PAYEE",
        "DEVICE_CHANGED",
        "FIVE_TRANSACTIONS_10_MINUTES",
        "NAMECHECK_MISMATCH_ACCEPTED",
    )


def test_retry_excludes_current_payment_from_history_snapshot():
    event = screening_event()
    history = InMemoryRiskHistory()
    _record_prior(history, event, minutes_ago=20)
    pipeline = RiskEvaluationPipeline()

    before = history.snapshot(
        event.payload.customer_id,
        event.occurred_at,
        exclude_payment_id=event.payload.payment_id,
    )
    first = pipeline.evaluate(event, before)
    history.record(event)
    after = history.snapshot(
        event.payload.customer_id,
        event.occurred_at,
        exclude_payment_id=event.payload.payment_id,
    )
    retried = pipeline.evaluate(event, after)

    assert retried == first


def test_first_device_is_not_a_change_but_subsequent_difference_is():
    event = screening_event()
    history = InMemoryRiskHistory()
    pipeline = RiskEvaluationPipeline()
    empty = history.snapshot(event.payload.customer_id, event.occurred_at)
    assert pipeline.evaluate(event, empty).audit_record.features.device_changed is False

    _record_prior(
        history,
        event,
        minutes_ago=20,
        device_token="different_device_1",
    )
    changed = history.snapshot(event.payload.customer_id, event.occurred_at)
    assert pipeline.evaluate(event, changed).audit_record.features.device_changed is True


@pytest.mark.parametrize(
    ("score", "expected"),
    [
        (39, RiskAction.APPROVE),
        (40, RiskAction.HOLD),
        (69, RiskAction.HOLD),
        (70, RiskAction.BLOCK),
    ],
)
def test_decision_threshold_boundaries(score, expected):
    assert DecisionClassifier().decide(score) is expected
