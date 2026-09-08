#!/bin/sh
set -eu

AWS_REGION="ap-southeast-2"
ACCOUNT_ID="000000000000"
DLQ_NAME="payment-events-dlq"
QUEUE_NAME="payment-events"
FRAUD_DLQ_NAME="fraud-events-dlq"
FRAUD_QUEUE_NAME="fraud-events"

awslocal sqs create-queue \
  --region "$AWS_REGION" \
  --queue-name "$DLQ_NAME"

DLQ_ARN="arn:aws:sqs:${AWS_REGION}:${ACCOUNT_ID}:${DLQ_NAME}"

awslocal sqs create-queue \
  --region "$AWS_REGION" \
  --queue-name "$QUEUE_NAME" \
  --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\",\"VisibilityTimeout\":\"30\"}"

awslocal sqs create-queue \
  --region "$AWS_REGION" \
  --queue-name "$FRAUD_DLQ_NAME"

FRAUD_DLQ_ARN="arn:aws:sqs:${AWS_REGION}:${ACCOUNT_ID}:${FRAUD_DLQ_NAME}"

awslocal sqs create-queue \
  --region "$AWS_REGION" \
  --queue-name "$FRAUD_QUEUE_NAME" \
  --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${FRAUD_DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\",\"VisibilityTimeout\":\"30\"}"

awslocal sns create-topic \
  --region "$AWS_REGION" \
  --name "risk-decisions"
