# SentinelPay Resume-Ready AWS and Performance Plan

## 1. Outcome and definition of done

Turn the current “Day 16” codebase into a reproducible, backend-fintech portfolio release with:

- An ephemeral AWS environment in `ap-southeast-2`, deployable and destroyable through protected GitHub Actions and committed AWS CLI automation.
- AWS-managed private infrastructure: CloudFront, S3, Cognito, internal ALB, ECS Fargate, RDS PostgreSQL, SNS/SQS, Secrets Manager, KMS, and CloudWatch.
- Measured API, payment-pipeline, queue-recovery, and OpenAI metrics backed by raw evidence and financial-integrity checks.
- A recruiter-facing proof pack: polished README, diagrams, screenshots, benchmark report, threat model, three-minute demo script, MIT license, GitHub metadata, and evidence-backed resume bullets.
- A tagged `v1.0.0` release after the environment has been rebuilt from scratch, tested, recorded, and destroyed.

The latest GitHub CI is green. The main gaps are the empty root README, incomplete AWS deployment automation, no runtime observability/load tooling, and stale top-level documentation.

## 2. Application and interface readiness

### Runtime and deployment contracts

- Add production-grade, non-root container images for the Spring service and Python service. Reuse the Python image with explicit API, worker, and migration commands.
- Make `docker compose up --build` start the full local stack: PostgreSQL, LocalStack, migrations, payments API, fraud worker/API, and dashboard.
- Split AWS client configuration by profile:
  - `local/test`: explicit LocalStack endpoint and static test credentials.
  - `cloud`: no endpoint override and the ECS task-role credential chain.
- Replace Java’s direct SQS event publishing with an SNS payment-events topic and filtered SQS subscriptions, matching the accepted architecture. Keep the existing versioned event envelope and add `eventType`, `schemaVersion`, and `traceparent` message attributes.
- Introduce bounded PostgreSQL connection pooling/reconnection for FastAPI and the fraud worker; the current shared API connection is not safe under concurrent requests.
- Disable automatic schema migration in runtime services. Add one-off migration and demo-seed task commands so deployments apply migrations before starting new tasks.
- Derive cloud user roles only from validated Cognito groups; never accept an analyst role from request data.
- Keep the pinned `gpt-5.4-mini-2026-03-17` snapshot, strict `text.format` JSON Schema, `store=false`, bounded tokens, timeout, and two jittered retries. The model supports the Responses API and Structured Outputs according to [official OpenAI documentation](https://developers.openai.com/api/docs/models/gpt-5.4-mini); `store=false` follows the [Responses API guidance](https://developers.openai.com/api/docs/guides/migrate-to-responses).
- In AWS, every rule-proposal request uses the live OpenAI API. CI and ordinary local tests retain the deterministic stub.

### Public and internal interfaces

- Preserve all existing customer and analyst REST contracts.
- Production browser calls remain `/api/*`; a CloudFront Function strips `/api` before forwarding to Spring.
- Route `/webhooks/psp` separately to the API without caching or application JWT, while preserving its HMAC and replay validation.
- Add and test:
  - Spring `/actuator/health/liveness` and `/actuator/health/readiness`.
  - Fraud API `/health/live` and `/health/ready`, with readiness checking its model and database dependency.
  - `X-Correlation-ID` response headers and propagation through events, logs, and traces.
- Do not expose metrics, database administration, migration, seed, or load-test endpoints publicly.
- Add configuration contracts for topic/queue identifiers, Cognito issuer/client/audience, OTLP endpoint, runtime DB secrets, internal token, PSP secret, and OpenAI secret.

## 3. AWS infrastructure and delivery

### Direct AWS layout and architecture

- Keep idempotent AWS CLI scripts and checked-in configuration under `infra/aws` for persistent resources, demo resources, verification, and teardown.
- Keep persistent resources—GitHub OIDC roles, ECR repositories, lifecycle policies, and the AWS Budget—separate from the destroyable demo environment.
- Every create/update script must tag resources with `Project=SentinelPay`, `Environment=demo`, and `ManagedBy=aws-cli`; scripts must discover existing resources before creating them and record non-secret outputs for later deployment steps.
- Build across two Availability Zones:
  - Public subnets contain only one NAT gateway.
  - Private application subnets contain the internal ALB and ECS tasks.
  - Isolated database subnets contain RDS with no internet route.
- Use CloudFront with:
  - A private S3 origin using Origin Access Control for the SPA.
  - An internal ALB VPC origin for `/api/*` and `/webhooks/*`.
  - Viewer HTTPS, disabled API caching, required header forwarding, SPA route rewriting, and security headers.
  - No custom domain or Route 53 in v1.
- This supersedes ADR 0011’s internet-facing ALB: current [CloudFront VPC origins](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-vpc-origins.html) support internal ALBs and make CloudFront the only public application entry point.

### Compute, data, and messaging defaults

- ECS Fargate, Linux/x86-64, one task per workload:
  - Payments: 1 vCPU / 2 GiB.
  - Fraud API: 0.5 vCPU / 2 GiB.
  - Fraud worker: 0.5 vCPU / 2 GiB.
- Use Cloud Map private discovery for payments-to-fraud API traffic; only the payments security group may reach fraud port `8001`.
- Use PostgreSQL 17 on Single-AZ `db.t4g.micro`, 20 GiB gp3, storage encryption, one-day backup retention, and no final snapshot because all cloud data is synthetic.
- Provision payment-events and risk-decisions SNS topics, three workload queues, matching DLQs, redrive policies, encryption, subscription filters, and exact queue policies.
- Store separate payments/fraud database credentials, internal token, PSP secret, and OpenAI key in Secrets Manager. Secret values never enter command history or source control.
- A protected deployment workflow generates internal/database secrets and copies the OpenAI key from a GitHub Environment secret into Secrets Manager.
- Give payments, fraud API, fraud worker, migration, and execution concerns separate IAM roles. Restrict queue/topic/secret access to exact ARNs; document the few telemetry actions that require broad resource scope.
- Create idempotent seeding for two demo customers, one analyst, funded wallets, and a separate pool of 50 synthetic performance users.

### CI/CD and cost controls

- Pull requests run Java, Python, frontend, contract, migration, AWS script linting, and security checks without AWS credentials.
- Security gates include package audits, Gitleaks, Trivy filesystem/IaC/image scans, pinned base images, and Syft SBOMs. Pin GitHub Actions by commit SHA.
- Deployment is `workflow_dispatch` only, protected by the `aws-demo` GitHub Environment and a concurrency lock. GitHub obtains temporary AWS credentials through OIDC; no long-lived AWS access keys are stored, following [GitHub’s AWS OIDC guidance](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws).
- Deployment order:
  1. Run all CI/security gates.
  2. Build and scan images; push immutable commit-SHA digests.
  3. Create or update infrastructure with first-deploy services held at zero.
  4. Populate secrets, run migration tasks, and seed Cognito/database demo identities.
  5. Deploy ECS task definitions by image digest and wait for healthy steady state.
  6. Build the SPA with recorded Cognito deployment outputs, upload to S3, and invalidate CloudFront.
  7. Run authenticated smoke and end-to-end acceptance tests.
- Enable ECS health checks, deployment circuit breakers, and CloudWatch-alarm rollback; both mechanisms support automatic rollback of failed rolling deployments per [AWS ECS documentation](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/deployment-failure-detection.html).
- Add a manual destroy workflow requiring the typed confirmation `DESTROY demo`. Keep only bootstrap state, ECR’s latest ten digests, the GitHub release, and committed evidence.
- Create an AWS Budget with a configurable default of US$30 and 50/80/100% actual/forecast alerts. The A$50 goal depends on destroying the environment after each demo; it is not an always-on cost claim.

## 4. Observability, performance, and verification

### Telemetry

- Use OpenTelemetry/ADOT and CloudWatch Application Signals for Java and Python, with an ECS telemetry sidecar and trace-to-log correlation. Application Signals supports ECS and both runtimes according to the [AWS support documentation](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch-Application-Signals-supportmatrix.html).
- Emit structured JSON logs to stdout with environment, service, correlation ID, trace/span IDs, event type, safe outcome, and failure class. Never log tokens, secrets, prompts, raw request bodies, names, or wallet identifiers.
- Add low-cardinality metrics for:
  - HTTP request latency/error rate.
  - Payment creation and terminal-state latency.
  - Outbox lag and oldest unpublished event.
  - Fraud-decision latency and action counts.
  - Queue age/depth and DLQ depth.
  - Active reservation age.
  - Invalid/replayed webhooks.
  - Open reconciliation discrepancies.
  - OpenAI latency, failures, input/output tokens, and validation failures.
- Build one CloudWatch dashboard and alarms for API 5xx/latency, unhealthy ECS tasks, RDS saturation/storage, queue age, any DLQ message, outbox lag, stale reservations, and OpenAI failures.

### Reproducible performance suite

- Add k6 scenarios executed through CloudFront using real Cognito access tokens. A Playwright helper authenticates the 50 synthetic users and writes tokens only to an ignored temporary file.
- Run standard configuration first to prove the database-backed rate limits. Use an explicit `performance_mode` deployment setting only for capacity tests, then restore normal limits.
- Execute each measured scenario three times after a two-minute warm-up:
  - Read workload: 25 requests/second for 10 minutes.
  - Payment workload: 10 payments/second for 10 minutes.
  - Burst workload: 30 payments/second for two minutes.
  - Concurrent idempotency retries using the same key.
  - Ramp from 1 to 40 requests/second to identify the first SLO-breaking point.
  - Fraud-worker outage: pause it for two minutes, create backlog, restore it, and measure drain/recovery.
- Initial acceptance thresholds:
  - Synchronous API p95 ≤ 500 ms and p99 ≤ 1 second.
  - Non-intentional error rate < 1%.
  - Payment creation-to-terminal p95 ≤ 45 seconds, accounting for the intentional 30-second fraud lateness window.
  - Fraud backlog recovers within five minutes after worker restoration.
  - Zero financial-integrity violations.
- Run five controlled live OpenAI proposal generations and report median/range latency, token use, cost estimate, schema-validation rate, and failures. Do not present p95 from only five calls.
- Capture commit SHA, region, task sizes, RDS class, deployment settings, dataset size, test duration, raw k6 summary, CloudWatch screenshots, and before/after tuning results.
- Tune only a measured bottleneck—query/index, connection pool, batch size, polling interval, or consumer concurrency—and rerun the identical test. Never invent a performance claim before this evidence exists.

### Test and acceptance matrix

- Unit and contract tests cover JWT/audience/groups, event attributes, trace propagation, metric dimensions, OpenAI failures, and health semantics.
- Testcontainers cover migrations, SNS/SQS redrive/filtering, concurrent payment creation, outbox/inbox idempotency, and database reconnection.
- Post-run SQL assertions prove balanced ledger transactions, one settlement per payment, valid reservations, non-negative available balances, and no duplicate business effect.
- AWS deployment checks cover script linting, policy validation, public-access blocks, private RDS/tasks/ALB, exact IAM policies, and destroy/recreate.
- Cloud acceptance covers Cognito PKCE, customer payment, held-payment analyst review, signed webhook rejection/acceptance, reconciliation, live OpenAI proposal/approval, trace lookup, DLQ alarm, rollback from an unhealthy task, and failed direct access to S3/RDS/ALB.
- Add frontend component tests plus a Playwright smoke journey for customer and analyst roles.
- `v1.0.0` is blocked until clean-state local startup, clean-state AWS deployment, rollback, performance thresholds, security scans, and teardown all pass.

## 5. Recruiter proof pack and assumptions

### Deliverables

- Replace the root README with a recruiter-first overview, architecture flow, feature highlights, local/AWS quick starts, CI badge, security model, measured results, cost/availability caveats, screenshots, video link, and documentation index.
- Replace the stale architecture/API documents and add:
  - Current system/event/deployment diagrams.
  - Threat model and trust boundaries.
  - Operations, rollback, incident, migration, and teardown runbooks.
  - Performance methodology and raw-summary report.
  - ADR superseding the public ALB decision.
- Surface the existing model evidence honestly: 89.7% precision, 84.2% recall, 0.995 ROC-AUC, and 0.929 average precision on 1,440 seeded synthetic validation rows; explicitly state that labels are synthetic and were not used to fit the Isolation Forest.
- Add an MIT license, GitHub description/topics, release notes, SBOMs, and architecture assets.
- Produce a three-minute demo script covering idempotency, risk explanation, held-payment review, signed webhook security, reconciliation, live human-approved OpenAI rule creation, tracing, and one injected worker failure.
- Draft final resume bullets only after runtime measurements exist, using exact numbers and linking each claim to the committed report.

### Locked assumptions and exclusions

- Region is `ap-southeast-2`; CloudFront and Cognito-generated domains are sufficient.
- The AWS account can create IAM, networking, CloudFront, Cognito, ECS, RDS, KMS, Budgets, and OIDC resources.
- A billed OpenAI API project and GitHub Environment secret are available.
- All users, payments, names, credentials, and benchmark data are synthetic.
- The environment is deliberately single-NAT, Single-AZ RDS, and one task per service; it must be described as cost-shaped and non-HA.
- Real banking/PSP integration, cards/PCI, multi-region, autoscaling, Multi-AZ RDS, custom domains, WAF, and continuous public hosting are out of scope for v1.
- CI remains deterministic and never calls OpenAI; “always live” applies to the deployed AWS rule-proposal workflow.
