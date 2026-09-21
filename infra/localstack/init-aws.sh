#!/bin/sh
set -eu

AWS_REGION="ap-southeast-2"
ACCOUNT_ID="000000000000"
PAYMENT_DLQ_NAME="payment-events-dlq"
PAYMENT_QUEUE_NAME="payment-events"
FRAUD_DLQ_NAME="fraud-events-dlq"
FRAUD_QUEUE_NAME="fraud-events"
RISK_DECISIONS_DLQ_NAME="risk-decisions-dlq"
RISK_DECISIONS_QUEUE_NAME="risk-decisions"

awslocal sqs create-queue \
  --region "$AWS_REGION" \
  --queue-name "$PAYMENT_DLQ_NAME"

PAYMENT_DLQ_ARN="arn:aws:sqs:${AWS_REGION}:${ACCOUNT_ID}:${PAYMENT_DLQ_NAME}"
PAYMENT_QUEUE_URL="$(awslocal sqs create-queue \
  --region "$AWS_REGION" \
  --queue-name "$PAYMENT_QUEUE_NAME" \
  --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${PAYMENT_DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\",\"VisibilityTimeout\":\"30\"}" \
  --query QueueUrl \
  --output text)"
PAYMENT_QUEUE_ARN="arn:aws:sqs:${AWS_REGION}:${ACCOUNT_ID}:${PAYMENT_QUEUE_NAME}"

awslocal sqs create-queue \
  --region "$AWS_REGION" \
  --queue-name "$FRAUD_DLQ_NAME"

FRAUD_DLQ_ARN="arn:aws:sqs:${AWS_REGION}:${ACCOUNT_ID}:${FRAUD_DLQ_NAME}"
FRAUD_QUEUE_URL="$(awslocal sqs create-queue \
  --region "$AWS_REGION" \
  --queue-name "$FRAUD_QUEUE_NAME" \
  --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${FRAUD_DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\",\"VisibilityTimeout\":\"30\"}" \
  --query QueueUrl \
  --output text)"
FRAUD_QUEUE_ARN="arn:aws:sqs:${AWS_REGION}:${ACCOUNT_ID}:${FRAUD_QUEUE_NAME}"

PAYMENT_TOPIC_ARN="$(awslocal sns create-topic \
  --region "$AWS_REGION" \
  --name "payment-events" \
  --query TopicArn \
  --output text)"

awslocal sqs set-queue-attributes \
  --region "$AWS_REGION" \
  --queue-url "$PAYMENT_QUEUE_URL" \
  --attributes "{\"Policy\":\"{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[{\\\"Effect\\\":\\\"Allow\\\",\\\"Principal\\\":{\\\"Service\\\":\\\"sns.amazonaws.com\\\"},\\\"Action\\\":\\\"sqs:SendMessage\\\",\\\"Resource\\\":\\\"${PAYMENT_QUEUE_ARN}\\\",\\\"Condition\\\":{\\\"ArnEquals\\\":{\\\"aws:SourceArn\\\":\\\"${PAYMENT_TOPIC_ARN}\\\"}}}]}\"}"

awslocal sqs set-queue-attributes \
  --region "$AWS_REGION" \
  --queue-url "$FRAUD_QUEUE_URL" \
  --attributes "{\"Policy\":\"{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[{\\\"Effect\\\":\\\"Allow\\\",\\\"Principal\\\":{\\\"Service\\\":\\\"sns.amazonaws.com\\\"},\\\"Action\\\":\\\"sqs:SendMessage\\\",\\\"Resource\\\":\\\"${FRAUD_QUEUE_ARN}\\\",\\\"Condition\\\":{\\\"ArnEquals\\\":{\\\"aws:SourceArn\\\":\\\"${PAYMENT_TOPIC_ARN}\\\"}}}]}\"}"

PAYMENT_SUBSCRIPTION_ARN="$(awslocal sns subscribe \
  --region "$AWS_REGION" \
  --topic-arn "$PAYMENT_TOPIC_ARN" \
  --protocol sqs \
  --notification-endpoint "$PAYMENT_QUEUE_ARN" \
  --attributes RawMessageDelivery=true \
  --return-subscription-arn \
  --query SubscriptionArn \
  --output text)"

awslocal sns set-subscription-attributes \
  --region "$AWS_REGION" \
  --subscription-arn "$PAYMENT_SUBSCRIPTION_ARN" \
  --attribute-name FilterPolicy \
  --attribute-value '{"eventType":["PAYMENT_CREATED","PAYMENT_APPROVED"]}'

FRAUD_SUBSCRIPTION_ARN="$(awslocal sns subscribe \
  --region "$AWS_REGION" \
  --topic-arn "$PAYMENT_TOPIC_ARN" \
  --protocol sqs \
  --notification-endpoint "$FRAUD_QUEUE_ARN" \
  --attributes RawMessageDelivery=true \
  --return-subscription-arn \
  --query SubscriptionArn \
  --output text)"

awslocal sns set-subscription-attributes \
  --region "$AWS_REGION" \
  --subscription-arn "$FRAUD_SUBSCRIPTION_ARN" \
  --attribute-name FilterPolicy \
  --attribute-value '{"eventType":["PAYMENT_SCREENING_REQUESTED"]}'

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
