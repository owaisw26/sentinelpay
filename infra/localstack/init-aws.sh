#!/bin/sh
set -eu

AWS_REGION="ap-southeast-2"
ACCOUNT_ID="000000000000"
DLQ_NAME="payment-events-dlq"
QUEUE_NAME="payment-events"
FRAUD_DLQ_NAME="fraud-events-dlq"
FRAUD_QUEUE_NAME="fraud-events"
RISK_DECISIONS_DLQ_NAME="risk-decisions-dlq"
RISK_DECISIONS_QUEUE_NAME="risk-decisions"

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

RISK_TOPIC_ARN="$(awslocal sns create-topic \
  --region "$AWS_REGION" \
  --name "risk-decisions" \
  --query TopicArn \
  --output text)"

awslocal sqs create-queue \
  --region "$AWS_REGION" \
  --queue-name "$RISK_DECISIONS_DLQ_NAME"

RISK_DLQ_ARN="arn:aws:sqs:${AWS_REGION}:${ACCOUNT_ID}:${RISK_DECISIONS_DLQ_NAME}"

RISK_QUEUE_URL="$(awslocal sqs create-queue \
  --region "$AWS_REGION" \
  --queue-name "$RISK_DECISIONS_QUEUE_NAME" \
  --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${RISK_DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\",\"VisibilityTimeout\":\"30\"}" \
  --query QueueUrl \
  --output text)"

RISK_QUEUE_ARN="arn:aws:sqs:${AWS_REGION}:${ACCOUNT_ID}:${RISK_DECISIONS_QUEUE_NAME}"

awslocal sqs set-queue-attributes \
  --region "$AWS_REGION" \
  --queue-url "$RISK_QUEUE_URL" \
  --attributes "{\"Policy\":\"{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[{\\\"Effect\\\":\\\"Allow\\\",\\\"Principal\\\":{\\\"Service\\\":\\\"sns.amazonaws.com\\\"},\\\"Action\\\":\\\"sqs:SendMessage\\\",\\\"Resource\\\":\\\"${RISK_QUEUE_ARN}\\\",\\\"Condition\\\":{\\\"ArnEquals\\\":{\\\"aws:SourceArn\\\":\\\"${RISK_TOPIC_ARN}\\\"}}}]}\"}"

awslocal sns subscribe \
  --region "$AWS_REGION" \
  --topic-arn "$RISK_TOPIC_ARN" \
  --protocol sqs \
  --notification-endpoint "$RISK_QUEUE_ARN"
