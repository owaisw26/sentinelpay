#!/bin/sh
set -eu

AWS_REGION="ap-southeast-2"
ACCOUNT_ID="000000000000"
DLQ_NAME="payment-events-dlq"
QUEUE_NAME="payment-events"

awslocal sqs create-queue \
  --region "$AWS_REGION" \
  --queue-name "$DLQ_NAME"

DLQ_ARN="arn:aws:sqs:${AWS_REGION}:${ACCOUNT_ID}:${DLQ_NAME}"

awslocal sqs create-queue \
  --region "$AWS_REGION" \
  --queue-name "$QUEUE_NAME" \
  --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\",\"VisibilityTimeout\":\"30\"}"
