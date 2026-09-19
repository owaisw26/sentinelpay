# ADR 0011: Private AWS foundation with workload-scoped IAM

## Status

Accepted for Day 17.

## Context

SentinelPay needs a reproducible AWS foundation for an ephemeral portfolio
environment without presenting demo-level availability as a production
guarantee. The browser application and signed PSP webhook must reach the Java
API, while the application containers and financial database must not be
directly reachable from the internet. The Java payments service, Python fraud
worker, fraud API, and migration task have different AWS and database
responsibilities, so a shared application identity would give a compromised
workload unnecessary access.

Private ECS tasks still require outbound connectivity. They call AWS services,
the payments service resolves Cognito signing keys and will call a PSP, and the
fraud service makes an explicitly enabled OpenAI request. Removing internet
egress with VPC endpoints alone would therefore not support the complete cloud
acceptance flow.

## Considered alternatives

1. Cost-shaped private demo topology. Use public subnets only for the
   internet-facing ALB and one zonal NAT gateway. Run ECS tasks without public
   IP addresses in private application subnets and RDS in isolated database
   subnets. Use one desired task per workload and a Single-AZ RDS instance.
   This preserves the security boundaries but accepts temporary loss of
   outbound processing or task replacement when the NAT gateway or its
   Availability Zone is unavailable.
2. Multi-AZ production topology. Use resilient egress for every active
   Availability Zone, at least two tasks for user-facing and continuously
   running workloads, and Multi-AZ RDS. This reduces single-AZ failure impact
   but adds recurring cost and does not materially improve the evidence
   produced by a short-lived portfolio environment.

For IAM, we considered a shared runtime role and workload-specific roles. A
shared role reduces Terraform resources but makes the union of all application
permissions available to every container. Workload-specific roles add policy
definitions but let AWS authorization reflect each service's actual message,
secret, and deployment responsibilities.

## Decision

Use the cost-shaped private demo topology by default. CloudFront serves the SPA
from a private S3 origin and routes API traffic to an internet-facing ALB. The
ALB is the only public application ingress. ECS tasks run without public IP
addresses in private application subnets. The payments task accepts application
traffic only from the ALB, the fraud API accepts internal traffic only from the
payments task, and the fraud worker accepts no inbound application traffic.
RDS is not publicly accessible and accepts PostgreSQL connections only from
the payments and fraud security groups.

Create subnets across at least two Availability Zones, but use one zonal NAT
gateway for demo egress. Expose an availability-mode input that can change the
topology to per-AZ resilient egress, multiple ECS tasks distributed across
Availability Zones, and Multi-AZ RDS. Enabling one of these controls alone must
not be described as making the system highly available.

Give every workload a distinct ECS task role and task execution role:

- The payments task role may publish only to its event topic and consume only
  the payment-orchestration and risk-decision queues.
- The fraud worker task role may consume only the fraud-event queue and publish
  only to the risk-decision topic.
- The fraud API and migration task receive no runtime AWS data-plane permission
  unless their implementations demonstrate a specific requirement.
- Each execution role may pull the required images, write to its own log group,
  and retrieve only the secrets injected into that workload.

Task trust policies allow `ecs-tasks.amazonaws.com` and constrain assumption by
the deployment account and ECS source ARN. Identity policies use exact resource
ARNs for data-plane operations. Any AWS operation that cannot support
resource-level scoping is isolated and documented rather than used to justify
broad application permissions. Queue resource policies allow SNS delivery only
from the intended topic and account. KMS key policies and grants are restricted
to the services and roles that use the encrypted resource.

IAM isolation does not replace database isolation. The payments and fraud
workloads retain separate PostgreSQL roles and schema privileges.

The architectural invariants are:

- No application container or database is directly reachable from the
  internet.
- Compromise of one workload does not grant access to another workload's
  queues, topics, secrets, or database privileges.
- Loss of egress may delay work, but durable queues, the outbox, idempotent
  consumers, and database transactions prevent loss or duplicate financial
  effects when processing resumes.

## Consequences

- A single NAT gateway and Single-AZ database are deliberate demo availability
  risks, not production defaults.
- During a NAT or Availability Zone failure, internal traffic may continue but
  AWS API calls, external provider calls, image pulls, secret injection, log
  delivery, and replacement task startup can be disrupted.
- The public ALB, private application tier, and isolated database tier make the
  trust boundaries visible and independently testable.
- Workload-specific roles and queue policies create more Terraform resources,
  but policy review and security-scan findings map to a specific workload.
- A production rollout must enable the multi-AZ topology and reassess capacity,
  backups, recovery objectives, private endpoints, controlled egress, WAF,
  and database credential rotation using measured operational requirements.
