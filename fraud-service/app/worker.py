from __future__ import annotations

import logging
import os

from app.adapters import (
    LoggingAuditSink,
    RiskMessageHandler,
    SnsDecisionPublisher,
    SqsRiskConsumer,
    SqsSnsEventIngestor,
)
from app.history import InMemoryRiskHistory
from app.risk import RiskEvaluationPipeline


def build_worker() -> SqsRiskConsumer:
    import boto3

    region = os.getenv("AWS_REGION", "ap-southeast-2")
    endpoint_url = os.getenv("AWS_ENDPOINT")
    queue_url = os.environ["FRAUD_EVENTS_QUEUE_URL"]
    topic_arn = os.environ["RISK_DECISIONS_TOPIC_ARN"]
    client_options = {"region_name": region}
    if endpoint_url:
        client_options["endpoint_url"] = endpoint_url

    history = InMemoryRiskHistory()
    handler = RiskMessageHandler(
        SqsSnsEventIngestor(),
        history,
        RiskEvaluationPipeline(),
        SnsDecisionPublisher(boto3.client("sns", **client_options), topic_arn),
        LoggingAuditSink(),
    )
    return SqsRiskConsumer(
        boto3.client("sqs", **client_options), queue_url, handler
    )


def main() -> None:
    logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
    worker = build_worker()
    while True:
        worker.poll_once()


if __name__ == "__main__":
    main()
