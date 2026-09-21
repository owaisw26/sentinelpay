# SentinelPay direct AWS deployment

SentinelPay uses AWS CLI-driven deployment in `ap-southeast-2`. Terraform is
intentionally reserved for a separate portfolio project.

## Persistent resources created

- Private ECR repositories: `sentinelpay/payments`, `sentinelpay/fraud`, and
  `sentinelpay/dashboard`.
- Immutable tags, scan-on-push, AES-256 encryption, and retention of the latest
  ten images on each repository.
- The existing account budget and GitHub `aws-demo` deployment environment.
- GitHub OIDC provider `token.actions.githubusercontent.com` and deployment role
  `sentinelpay-github-deploy`.
- The deployment role trust policy accepts only the protected `aws-demo`
  environment for the repository's immutable GitHub subject. Its current
  permissions are restricted to pushing and inspecting the three ECR
  repositories.

## Planned demo messaging resources

The production SNS/SQS resources have not been created yet. The deployment
will provision:

- Encrypted SNS topics: `sentinelpay-payment-events` and
  `sentinelpay-risk-decisions`.
- Encrypted SQS queues and matching DLQs for payment events, fraud events, and
  risk decisions.
- Exact queue policies permitting only the corresponding SNS topic.
- Raw-message subscriptions filtered by `eventType`:
  - Payments: `PAYMENT_CREATED`, `PAYMENT_APPROVED`
  - Fraud: `PAYMENT_SCREENING_REQUESTED`
  - Risk decisions: `RISK_DECISION_MADE`

## Deployment contract

The Spring `cloud` profile uses the default AWS SDK credential chain. ECS must
provide credentials through a task role; do not set static AWS access keys or
an endpoint override. Runtime services do not run database migrations.

The next deployment slice creates Secrets Manager entries, Cognito resources,
and the private network before any cost-bearing ECS, NAT, or RDS workload is
started.
