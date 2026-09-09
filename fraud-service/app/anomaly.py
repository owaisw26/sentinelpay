from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass
from math import log, log1p
from pathlib import Path
from typing import Any, Sequence

import numpy as np

from app.contracts import RiskFeatures


ANOMALY_FEATURE_VERSION = "anomaly-features-v1"
ANOMALY_MODEL_VERSION = "isolation-forest-v1"
ANOMALY_POINTS = 20
SEVERE_ANOMALY_POINTS = 30
FEATURE_NAMES = (
    "log_amount",
    "first_time_payee",
    "device_changed",
    "transaction_count_10_minutes",
    "accepted_name_mismatch",
)
FEATURE_REASON_CODES = (
    "ANOMALOUS_AMOUNT",
    "ANOMALOUS_FIRST_TIME_PAYEE",
    "ANOMALOUS_DEVICE_CHANGE",
    "ANOMALOUS_TRANSACTION_VELOCITY",
    "ANOMALOUS_NAMECHECK_MISMATCH",
)


@dataclass(frozen=True)
class AnomalyModelMetadata:
    model_version: str
    feature_version: str
    seed: int
    threshold: float
    severe_threshold: float
    centers: tuple[float, ...]
    scales: tuple[float, ...]
    sklearn_version: str
    numpy_version: str


@dataclass(frozen=True)
class AnomalyAssessment:
    anomaly_score: float
    contribution: int
    reason_codes: tuple[str, ...]


@dataclass(frozen=True)
class IsolationTree:
    children_left: tuple[int, ...]
    children_right: tuple[int, ...]
    features: tuple[int, ...]
    thresholds: tuple[float, ...]
    sample_counts: tuple[int, ...]

    def __post_init__(self) -> None:
        lengths = {
            len(self.children_left),
            len(self.children_right),
            len(self.features),
            len(self.thresholds),
            len(self.sample_counts),
        }
        if len(lengths) != 1 or not self.children_left:
            raise ValueError("invalid isolation tree artifact")


class AnomalyFeatureTransformer:
    """The single feature transformation used by training and serving."""

    version = ANOMALY_FEATURE_VERSION

    def transform(self, features: RiskFeatures) -> np.ndarray:
        return np.asarray(
            [
                log1p(float(features.amount)),
                float(features.first_time_payee),
                float(features.device_changed),
                float(min(features.transaction_count_10_minutes, 20)),
                float(features.accepted_name_mismatch),
            ],
            dtype=np.float32,
        )

    def transform_many(
        self, feature_rows: Sequence[RiskFeatures]
    ) -> np.ndarray:
        if not feature_rows:
            return np.empty((0, len(FEATURE_NAMES)), dtype=np.float32)
        return np.vstack([self.transform(row) for row in feature_rows])


class IsolationForestAnomalyModel:
    def __init__(
        self,
        trees: tuple[IsolationTree, ...],
        max_samples: int,
        metadata: AnomalyModelMetadata,
    ) -> None:
        if metadata.feature_version != ANOMALY_FEATURE_VERSION:
            raise ValueError("unsupported anomaly feature version")
        if len(metadata.centers) != len(FEATURE_NAMES):
            raise ValueError("invalid anomaly explanation centers")
        if len(metadata.scales) != len(FEATURE_NAMES):
            raise ValueError("invalid anomaly explanation scales")
        if any(scale <= 0 for scale in metadata.scales):
            raise ValueError("anomaly explanation scales must be positive")
        if metadata.severe_threshold < metadata.threshold:
            raise ValueError("severe threshold cannot be below threshold")
        if not trees or max_samples <= 1:
            raise ValueError("invalid isolation forest artifact")
        self._trees = trees
        self._max_samples = max_samples
        self.metadata = metadata
        self._transformer = AnomalyFeatureTransformer()

    @property
    def version(self) -> str:
        return self.metadata.model_version

    def assess(self, features: RiskFeatures) -> AnomalyAssessment:
        vector = self._transformer.transform(features)
        anomaly_score = self._score(vector)
        if anomaly_score < self.metadata.threshold:
            return AnomalyAssessment(anomaly_score, 0, ())

        contribution = (
            SEVERE_ANOMALY_POINTS
            if anomaly_score >= self.metadata.severe_threshold
            else ANOMALY_POINTS
        )
        return AnomalyAssessment(
            anomaly_score,
            contribution,
            self._explain(vector),
        )

    def score(self, features: RiskFeatures) -> float:
        return self._score(self._transformer.transform(features))

    def _score(self, vector: np.ndarray) -> float:
        total_depth = 0.0
        for tree in self._trees:
            node = 0
            depth = 0
            while tree.children_left[node] != tree.children_right[node]:
                feature = tree.features[node]
                node = (
                    tree.children_left[node]
                    if vector[feature] <= tree.thresholds[node]
                    else tree.children_right[node]
                )
                depth += 1
            total_depth += depth + _average_path_length(
                tree.sample_counts[node]
            )
        denominator = len(self._trees) * _average_path_length(
            self._max_samples
        )
        return float(2.0 ** (-total_depth / denominator))

    def _explain(self, vector: np.ndarray) -> tuple[str, ...]:
        centers = np.asarray(self.metadata.centers, dtype=np.float64)
        scales = np.asarray(self.metadata.scales, dtype=np.float64)
        deviations = np.abs(vector - centers) / scales
        ranked = sorted(
            range(len(FEATURE_NAMES)),
            key=lambda index: (-deviations[index], FEATURE_NAMES[index]),
        )
        material = [index for index in ranked if deviations[index] >= 1.0]
        selected = (material or ranked)[:2]
        return tuple(FEATURE_REASON_CODES[index] for index in selected)

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "formatVersion": 1,
            "metadata": asdict(self.metadata),
            "maxSamples": self._max_samples,
            "trees": [
                {
                    "childrenLeft": tree.children_left,
                    "childrenRight": tree.children_right,
                    "features": tree.features,
                    "thresholds": tree.thresholds,
                    "sampleCounts": tree.sample_counts,
                }
                for tree in self._trees
            ],
        }
        document = json.dumps(
            payload,
            allow_nan=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        temporary_path = path.with_name(f"{path.name}.tmp")
        temporary_path.write_text(f"{document}\n", encoding="utf-8")
        temporary_path.replace(path)

    @classmethod
    def load(
        cls, path: Path, *, expected_sha256: str | None = None
    ) -> IsolationForestAnomalyModel:
        if expected_sha256 is not None:
            actual = sha256_file(path)
            if actual != expected_sha256:
                raise ValueError(
                    "anomaly model checksum mismatch: "
                    f"expected {expected_sha256}, got {actual}"
                )
        payload = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(payload, dict) or payload.get("formatVersion") != 1:
            raise ValueError("unsupported anomaly model artifact")
        metadata_document = dict(payload["metadata"])
        metadata_document["centers"] = tuple(metadata_document["centers"])
        metadata_document["scales"] = tuple(metadata_document["scales"])
        metadata = AnomalyModelMetadata(**metadata_document)
        trees = tuple(
            IsolationTree(
                children_left=tuple(tree["childrenLeft"]),
                children_right=tuple(tree["childrenRight"]),
                features=tuple(tree["features"]),
                thresholds=tuple(tree["thresholds"]),
                sample_counts=tuple(tree["sampleCounts"]),
            )
            for tree in payload["trees"]
        )
        return cls(trees, payload["maxSamples"], metadata)

    @classmethod
    def from_sklearn(
        cls, estimator: Any, metadata: AnomalyModelMetadata
    ) -> IsolationForestAnomalyModel:
        trees = tuple(
            IsolationTree(
                children_left=tuple(
                    int(value) for value in item.tree_.children_left
                ),
                children_right=tuple(
                    int(value) for value in item.tree_.children_right
                ),
                features=tuple(int(value) for value in item.tree_.feature),
                thresholds=tuple(
                    float(value) for value in item.tree_.threshold
                ),
                sample_counts=tuple(
                    int(value) for value in item.tree_.n_node_samples
                ),
            )
            for item in estimator.estimators_
        )
        return cls(trees, int(estimator.max_samples_), metadata)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as artifact:
        for block in iter(lambda: artifact.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _average_path_length(sample_count: int) -> float:
    if sample_count <= 1:
        return 0.0
    if sample_count == 2:
        return 1.0
    return 2.0 * (log(sample_count - 1.0) + euler_gamma()) - (
        2.0 * (sample_count - 1.0) / sample_count
    )


def euler_gamma() -> float:
    # Kept as a function to make the scoring formula read like its definition.
    return 0.5772156649015329
