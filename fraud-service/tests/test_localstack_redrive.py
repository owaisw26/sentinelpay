import json
import os
import time
from uuid import uuid4

import boto3
import pytest

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


pytestmark = pytest.mark.localstack


@pytest.fixture
def redrive_queues():
    endpoint = os.getenv("LOCALSTACK_ENDPOINT")
    if not endpoint:
        pytest.skip("LOCALSTACK_ENDPOINT is not configured")

    sqs = boto3.client(
        "sqs",
        endpoint_url=endpoint,
        region_name="ap-southeast-2",
        aws_access_key_id="test",
        aws_secret_access_key="test",
    )
    suffix = uuid4().hex[:12]
    dlq_url = sqs.create_queue(QueueName=f"day12-fraud-dlq-{suffix}")[
        "QueueUrl"
    ]
    dlq_arn = sqs.get_queue_attributes(
        QueueUrl=dlq_url,
        AttributeNames=["QueueArn"],
    )["Attributes"]["QueueArn"]
    source_url = sqs.create_queue(
        QueueName=f"day12-fraud-events-{suffix}",
        Attributes={
            "VisibilityTimeout": "0",
            "RedrivePolicy": json.dumps(
                {
                    "deadLetterTargetArn": dlq_arn,
                    "maxReceiveCount": "2",
                }
            ),
        },
    )["QueueUrl"]
    try:
        yield sqs, source_url, dlq_url
    finally:
        sqs.delete_queue(QueueUrl=source_url)
        sqs.delete_queue(QueueUrl=dlq_url)


def test_malformed_and_incompatible_messages_reach_dlq(redrive_queues):
    sqs, source_url, dlq_url = redrive_queues
    incompatible = json.loads(event_body(screening_event()))
    incompatible["schemaVersion"] = 2
    rejected_bodies = {"not-json", json.dumps(incompatible)}
    for body in rejected_bodies:
        sqs.send_message(QueueUrl=source_url, MessageBody=body)

    consumer = SqsRiskConsumer(
        sqs,
        source_url,
        RiskMessageHandler(
            SqsSnsEventIngestor(),
            InMemoryRiskHistory(),
            RiskEvaluationPipeline(),
            InMemoryDecisionPublisher(),
            InMemoryAuditSink(),
        ),
        wait_time_seconds=0,
        visibility_timeout_seconds=0,
    )
    for _ in range(6):
        consumer.poll_once()

    dlq_bodies = set()
    for _ in range(20):
        response = sqs.receive_message(
            QueueUrl=dlq_url,
            MaxNumberOfMessages=10,
            WaitTimeSeconds=0,
        )
        dlq_bodies.update(
            message["Body"] for message in response.get("Messages", [])
        )
        if dlq_bodies == rejected_bodies:
            break
        time.sleep(0.1)

    assert dlq_bodies == rejected_bodies


def test_decision_adapter_publishes_versioned_event_through_sns():
    endpoint = os.getenv("LOCALSTACK_ENDPOINT")
    if not endpoint:
        pytest.skip("LOCALSTACK_ENDPOINT is not configured")
    client_options = {
        "endpoint_url": endpoint,
        "region_name": "ap-southeast-2",
        "aws_access_key_id": "test",
        "aws_secret_access_key": "test",
    }
    sqs = boto3.client("sqs", **client_options)
    sns = boto3.client("sns", **client_options)
    suffix = uuid4().hex[:12]
    queue_url = sqs.create_queue(QueueName=f"day12-decisions-{suffix}")[
        "QueueUrl"
    ]
    queue_arn = sqs.get_queue_attributes(
        QueueUrl=queue_url,
        AttributeNames=["QueueArn"],
    )["Attributes"]["QueueArn"]
    topic_arn = sns.create_topic(Name=f"day12-decisions-{suffix}")[
        "TopicArn"
    ]
    subscription_arn = sns.subscribe(
        TopicArn=topic_arn,
        Protocol="sqs",
        Endpoint=queue_arn,
        Attributes={"RawMessageDelivery": "true"},
        ReturnSubscriptionArn=True,
    )["SubscriptionArn"]
    try:
        event = screening_event()
        history = InMemoryRiskHistory()
        decision = RiskEvaluationPipeline().evaluate(
            event,
            history.snapshot(event.payload.customer_id, event.occurred_at),
        ).decision_event

        SnsDecisionPublisher(sns, topic_arn).publish(decision)

        messages = []
        for _ in range(20):
            messages = sqs.receive_message(
                QueueUrl=queue_url,
                MaxNumberOfMessages=1,
                WaitTimeSeconds=0,
            ).get("Messages", [])
            if messages:
                break
            time.sleep(0.1)
        assert len(messages) == 1
        published = json.loads(messages[0]["Body"])
        assert published["eventType"] == "RISK_DECISION_MADE"
        assert published["schemaVersion"] == 1
        assert published["payload"]["decisionId"] == str(
            decision.payload.decision_id
        )
    finally:
        sns.unsubscribe(SubscriptionArn=subscription_arn)
        sns.delete_topic(TopicArn=topic_arn)
        sqs.delete_queue(QueueUrl=queue_url)
