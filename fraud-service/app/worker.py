from __future__ import annotations

import json
import logging
import os
from datetime import timedelta
from pathlib import Path

from app.anomaly import IsolationForestAnomalyModel
from app.adapters import SqsRiskConsumer, SqsSnsEventIngestor
from app.risk import RiskEvaluationPipeline
from app.store import (
    DurableRiskMessageHandler,
    PostgresDecisionOutboxPublisher,
    PostgresRiskStore,
    apply_migrations,
)


def build_worker():
    import boto3
    import psycopg

    region = os.getenv("AWS_REGION", "ap-southeast-2")
    endpoint_url = os.getenv("AWS_ENDPOINT")
    queue_url = os.environ["FRAUD_EVENTS_QUEUE_URL"]
    topic_arn = os.environ["RISK_DECISIONS_TOPIC_ARN"]
    database_url = os.environ["FRAUD_DB_DSN"]
    allowed_lateness = timedelta(
        seconds=int(os.getenv("FRAUD_ALLOWED_LATENESS_SECONDS", "30"))
    )
    maximum_future_skew = timedelta(
        seconds=int(os.getenv("FRAUD_MAXIMUM_FUTURE_SKEW_SECONDS", "30"))
    )
    client_options = {"region_name": region}
    if endpoint_url:
        client_options["endpoint_url"] = endpoint_url

    connection = psycopg.connect(database_url, autocommit=True)
    if os.getenv("FRAUD_DB_MIGRATE", "false").lower() == "true":
        apply_migrations(connection)
    store = PostgresRiskStore(connection)
    handler = DurableRiskMessageHandler(
        SqsSnsEventIngestor(),
        store,
        allowed_lateness=allowed_lateness,
        maximum_future_skew=maximum_future_skew,
    )
    consumer = SqsRiskConsumer(
        boto3.client("sqs", **client_options), queue_url, handler
    )
    publisher = PostgresDecisionOutboxPublisher(
        store,
        boto3.client("sns", **client_options),
        topic_arn,
    )
    return consumer, store, publisher


def main() -> None:
    logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
    consumer, store, publisher = build_worker()
    pipeline = RiskEvaluationPipeline(anomaly_model=_load_anomaly_model())
    while True:
        consumer.poll_once()
        store.finalize_due(pipeline)
        publisher.publish_batch()


def _load_anomaly_model() -> IsolationForestAnomalyModel:
    service_root = Path(__file__).resolve().parent.parent
    artifact_path = Path(
        os.getenv(
            "FRAUD_MODEL_ARTIFACT",
            str(service_root / "artifacts" / "isolation_forest_v1.json"),
        )
    )
    expected_sha256 = os.getenv("FRAUD_MODEL_SHA256")
    if expected_sha256 is None:
        metrics_path = artifact_path.with_suffix(".metrics.json")
        metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
        expected_sha256 = metrics["artifact"]["sha256"]
    return IsolationForestAnomalyModel.load(
        artifact_path, expected_sha256=expected_sha256
    )


if __name__ == "__main__":
    main()
