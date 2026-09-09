import json
from decimal import Decimal
from pathlib import Path

import numpy as np
import pytest

from app.anomaly import (
    SEVERE_ANOMALY_POINTS,
    AnomalyAssessment,
    AnomalyFeatureTransformer,
    IsolationForestAnomalyModel,
    sha256_file,
)
from app.contracts import RiskAction, RiskFeatures
from app.history import InMemoryRiskHistory
from app.risk import RiskEvaluationPipeline
from app.synthetic import generate_synthetic_dataset
from app.train_anomaly import build_artifact, select_threshold, train_model
from app.worker import _load_anomaly_model
from tests.factories import screening_event


def test_threshold_maximizes_precision_while_meeting_recall_target():
    scores = np.asarray([0.9, 0.8, 0.7, 0.6, 0.5])
    labels = np.asarray([True, True, False, True, False])

    selected = select_threshold(scores, labels, target_recall=0.80)

    assert selected.threshold == 0.6
    assert selected.recall == 1.0
    assert selected.precision == 0.75


def test_training_and_serving_use_identical_feature_transformation():
    row = generate_synthetic_dataset(
        training_customers=1,
        validation_customers=1,
        transactions_per_customer=2,
    ).training[0]
    transformer = AnomalyFeatureTransformer()

    training_vector = transformer.transform_many([row.features])[0]
    serving_vector = transformer.transform(row.features)

    np.testing.assert_array_equal(training_vector, serving_vector)


def test_mixed_population_model_meets_recall_gate_and_is_bounded():
    model, metrics = train_model()
    validation = metrics["validation"]
    assert metrics["training"]["labelsUsedForFit"] is False
    assert validation["recall"] >= validation["targetRecall"]
    assert validation["maximumServingScoreDelta"] <= 1e-12

    suspicious = RiskFeatures(
        amount=Decimal("75000.00"),
        first_time_payee=True,
        device_changed=True,
        transaction_count_10_minutes=12,
        accepted_name_mismatch=True,
    )
    assessment = model.assess(suspicious)
    assert 0 < assessment.contribution <= SEVERE_ANOMALY_POINTS
    assert assessment.reason_codes


def test_artifact_rebuild_is_byte_reproducible(tmp_path):
    service_root = Path(__file__).resolve().parent.parent
    committed_metrics = json.loads(
        (service_root / "artifacts" / "isolation_forest_v1.metrics.json")
        .read_text(encoding="utf-8")
    )
    rebuilt_artifact = tmp_path / "isolation_forest_v1.json"
    rebuilt_metrics = tmp_path / "isolation_forest_v1.metrics.json"

    rebuilt = build_artifact(rebuilt_artifact, rebuilt_metrics)

    assert rebuilt["validation"] == committed_metrics["validation"]
    assert (
        sha256_file(rebuilt_artifact)
        == committed_metrics["artifact"]["sha256"]
    )


def test_artifact_checksum_is_verified():
    artifact = (
        Path(__file__).resolve().parent.parent
        / "artifacts"
        / "isolation_forest_v1.json"
    )

    with pytest.raises(ValueError, match="checksum mismatch"):
        IsolationForestAnomalyModel.load(artifact, expected_sha256="0" * 64)


def test_worker_loads_checksum_verified_committed_model(monkeypatch):
    monkeypatch.delenv("FRAUD_MODEL_ARTIFACT", raising=False)
    monkeypatch.delenv("FRAUD_MODEL_SHA256", raising=False)

    assert _load_anomaly_model().version == "isolation-forest-v1"


def test_anomaly_points_combine_without_hiding_rule_reasons():
    class StubModel:
        version = "stub-model-v1"

        def assess(self, features):
            return AnomalyAssessment(
                anomaly_score=0.75,
                contribution=30,
                reason_codes=("ANOMALOUS_TRANSACTION_VELOCITY",),
            )

    event = screening_event(amount=Decimal("5000.00"))
    result = RiskEvaluationPipeline(anomaly_model=StubModel()).evaluate(
        event,
        InMemoryRiskHistory().snapshot(
            event.payload.customer_id, event.occurred_at
        ),
    )

    assert result.audit_record.deterministic_score == 45
    assert result.audit_record.anomaly_contribution == 30
    assert result.decision_event.payload.score == 75
    assert result.decision_event.payload.action is RiskAction.BLOCK
    assert result.decision_event.payload.model_version == "stub-model-v1"
    assert result.decision_event.payload.reason_codes == (
        "AMOUNT_GTE_AUD_5000",
        "FIRST_TIME_PAYEE",
        "ANOMALOUS_TRANSACTION_VELOCITY",
    )
