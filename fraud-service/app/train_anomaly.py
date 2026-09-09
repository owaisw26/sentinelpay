from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
import sklearn
from sklearn.ensemble import IsolationForest
from sklearn.metrics import average_precision_score, roc_auc_score

from app.anomaly import (
    ANOMALY_FEATURE_VERSION,
    ANOMALY_MODEL_VERSION,
    AnomalyFeatureTransformer,
    AnomalyModelMetadata,
    IsolationForestAnomalyModel,
    sha256_file,
)
from app.synthetic import DEFAULT_SYNTHETIC_SEED, generate_synthetic_dataset


TARGET_RECALL = 0.80


@dataclass(frozen=True)
class ThresholdMetrics:
    threshold: float
    precision: float
    recall: float
    true_positives: int
    false_positives: int
    false_negatives: int


def select_threshold(
    scores: np.ndarray,
    labels: np.ndarray,
    *,
    target_recall: float = TARGET_RECALL,
) -> ThresholdMetrics:
    if scores.shape != labels.shape or scores.ndim != 1:
        raise ValueError("scores and labels must be equal one-dimensional arrays")
    positive_count = int(labels.sum())
    if positive_count == 0:
        raise ValueError("validation data must contain labelled fraud")

    candidates = np.unique(scores)
    best: ThresholdMetrics | None = None
    for threshold in candidates:
        predicted = scores >= threshold
        true_positives = int(np.logical_and(predicted, labels).sum())
        false_positives = int(np.logical_and(predicted, ~labels).sum())
        false_negatives = positive_count - true_positives
        recall = true_positives / positive_count
        predicted_count = true_positives + false_positives
        precision = true_positives / predicted_count if predicted_count else 0.0
        candidate = ThresholdMetrics(
            float(threshold),
            precision,
            recall,
            true_positives,
            false_positives,
            false_negatives,
        )
        if recall + 1e-12 < target_recall:
            continue
        if best is None or (candidate.precision, candidate.threshold) > (
            best.precision,
            best.threshold,
        ):
            best = candidate
    if best is None:
        raise ValueError("no anomaly threshold meets the recall target")
    return best


def train_model(
    *, seed: int = DEFAULT_SYNTHETIC_SEED
) -> tuple[IsolationForestAnomalyModel, dict[str, object]]:
    dataset = generate_synthetic_dataset(seed=seed)
    transformer = AnomalyFeatureTransformer()
    training_features = [row.features for row in dataset.training]
    validation_features = [row.features for row in dataset.validation]
    x_train = transformer.transform_many(training_features)
    x_validation = transformer.transform_many(validation_features)
    labels = np.asarray(
        [row.is_fraud for row in dataset.validation], dtype=np.bool_
    )

    estimator = IsolationForest(
        n_estimators=256,
        max_samples=512,
        contamination="auto",
        random_state=seed,
        n_jobs=1,
    )
    # Labels are deliberately not passed to fit: Option B is unsupervised.
    estimator.fit(x_train)
    validation_scores = -estimator.score_samples(x_validation)
    selected = select_threshold(validation_scores, labels)
    flagged_scores = validation_scores[validation_scores >= selected.threshold]
    severe_threshold = float(
        np.quantile(flagged_scores, 0.90, method="higher")
    )

    centers = np.median(x_train, axis=0)
    lower = np.quantile(x_train, 0.25, axis=0, method="linear")
    upper = np.quantile(x_train, 0.75, axis=0, method="linear")
    scales = np.maximum(upper - lower, 1.0)
    metadata = AnomalyModelMetadata(
        model_version=ANOMALY_MODEL_VERSION,
        feature_version=ANOMALY_FEATURE_VERSION,
        seed=seed,
        threshold=selected.threshold,
        severe_threshold=severe_threshold,
        centers=tuple(float(value) for value in centers),
        scales=tuple(float(value) for value in scales),
        sklearn_version=sklearn.__version__,
        numpy_version=np.__version__,
    )
    model = IsolationForestAnomalyModel.from_sklearn(estimator, metadata)
    serving_scores = np.asarray(
        [model.score(features) for features in validation_features]
    )
    maximum_serving_delta = float(
        np.max(np.abs(validation_scores - serving_scores))
    )
    if maximum_serving_delta > 1e-12:
        raise ValueError(
            "canonical serving scorer differs from the training scorer"
        )

    report: dict[str, object] = {
        "modelVersion": ANOMALY_MODEL_VERSION,
        "featureVersion": ANOMALY_FEATURE_VERSION,
        "seed": seed,
        "training": {
            "rows": len(dataset.training),
            "fraudRows": sum(row.is_fraud for row in dataset.training),
            "labelsUsedForFit": False,
        },
        "validation": {
            "rows": len(dataset.validation),
            "fraudRows": int(labels.sum()),
            "targetRecall": TARGET_RECALL,
            "threshold": selected.threshold,
            "severeThreshold": severe_threshold,
            "precision": selected.precision,
            "recall": selected.recall,
            "truePositives": selected.true_positives,
            "falsePositives": selected.false_positives,
            "falseNegatives": selected.false_negatives,
            "averagePrecision": float(
                average_precision_score(labels, validation_scores)
            ),
            "rocAuc": float(roc_auc_score(labels, validation_scores)),
            "maximumServingScoreDelta": maximum_serving_delta,
        },
        "runtime": {
            "numpy": np.__version__,
            "scikitLearn": sklearn.__version__,
            "fitThreads": 1,
        },
    }
    return model, report


def build_artifact(
    artifact_path: Path,
    metrics_path: Path,
    *,
    seed: int = DEFAULT_SYNTHETIC_SEED,
) -> dict[str, object]:
    model, report = train_model(seed=seed)
    model.save(artifact_path)
    report["artifact"] = {
        "path": artifact_path.name,
        "sha256": sha256_file(artifact_path),
    }
    metrics_path.parent.mkdir(parents=True, exist_ok=True)
    metrics_path.write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return report


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build the reproducible SentinelPay anomaly model"
    )
    parser.add_argument(
        "--artifact",
        type=Path,
        default=Path("artifacts/isolation_forest_v1.json"),
    )
    parser.add_argument(
        "--metrics",
        type=Path,
        default=Path("artifacts/isolation_forest_v1.metrics.json"),
    )
    parser.add_argument("--seed", type=int, default=DEFAULT_SYNTHETIC_SEED)
    arguments = parser.parse_args()
    report = build_artifact(
        arguments.artifact, arguments.metrics, seed=arguments.seed
    )
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
