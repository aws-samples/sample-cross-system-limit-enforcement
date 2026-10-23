# Security

## Disclaimer

This project is sample/educational code and is **not** intended for production use without additional security
hardening and review. It demonstrates a cross-system limit-enforcement pattern during a Strangler Fig migration;
limits, TTLs, failure policies, and the controls below should be adapted to your own regulatory and operational
requirements. See the **Accepted Risks and Justifications** section for what is intentionally left out of this demo.

**Compliance:** deployments that handle real financial transaction data may fall under regimes such as PCI-DSS,
SOX, or regional financial regulation. This sample does not claim to meet any such framework. You are responsible
for assessing which obligations apply to your deployment and for implementing the controls they require. Review the
[AWS Shared Responsibility Model](https://aws.amazon.com/compliance/shared-responsibility-model/) and
[AWS Compliance resources](https://aws.amazon.com/compliance/) when making that assessment.

---

## Reporting Vulnerabilities

If you discover a potential security issue in this project, please notify AWS/Amazon Security via the
[vulnerability reporting page](http://aws.amazon.com/security/vulnerability-reporting/). Do **not** create a
public GitHub issue.

---

## Authentication and Authorization

- The only internet-reachable route is `POST /api/transfers` on an **API Gateway HTTP API**. A **Cognito JWT
  authorizer** validates the bearer token (audience = app client ID) before the request reaches the backend;
  missing or invalid tokens return `401`.
- Internal endpoints are **not routed** by the gateway. `/internal/reconcile` (the reconciler trigger) and
  `/stub/*` are reachable only inside the cluster, never from the internet.
- **Self-service sign-up is disabled** on the Cognito user pool (`selfSignUpEnabled: false`) — accounts are
  created by an administrator, so discovering the public app client ID does not let anyone register.
- **Password policy:** minimum 12 characters with uppercase, lowercase, number, and symbol.
- The app client is **public** (`generateSecret: false`), so no client secret is embedded anywhere.

- **Per-customer authorization:** API Gateway overwrites the `X-Auth-Sub` request header with the `sub` claim
  from the validated JWT, so a client cannot supply its own value. The service resolves that `sub` to a
  `customerId` from a server-side binding (`PRINCIPAL_CUSTOMER_MAP`, set through the CDK context
  `principalCustomerMap`) and rejects any request for a different customer, or from an unbound principal,
  with `403 Forbidden`.

> **Limitation (demo):** the binding is config-seeded rather than read from a customer datastore. The
> `X-Auth-Sub` header is trusted only because the gateway sets it; a workload inside the cluster that calls
> the service directly could set the header itself. Use a customer datastore for the binding, and
> NetworkPolicies or in-process JWT validation, before any real use.

## Network Security

- All workloads and data stores run inside a **VPC** across 2 Availability Zones. The ALB, EKS nodes, Aurora, and
  ElastiCache are all in **private subnets**; only the NAT Gateway and Internet Gateway sit in public subnets.
- The **ALB is internal** (`internetFacing: false`). API Gateway reaches it over a private **VPC Link** — the load
  balancer is never exposed to the internet.
- **Security groups are least-privilege by port and source:**
  - Aurora accepts `3306` only from the EKS cluster security group.
  - ElastiCache accepts `6379` only from the EKS cluster security group.
  - The ALB accepts `80` only from the VPC Link security group.
  - Pods accept `8080` only from the ALB security group.
- Outbound internet access (e.g. image pulls) is via a single **NAT Gateway**; private subnets have no inbound
  internet path.

## Encryption

| Resource | Encryption | Notes |
|----------|-----------|-------|
| Aurora MySQL (reservation ledger) | At rest (`storageEncrypted: true`, AWS-managed KMS) | — |
| Secrets Manager (DB credentials) | At rest (AWS-managed KMS) | — |
| Client → API Gateway | In transit (HTTPS / TLS) | Default `execute-api` endpoint enforces TLS |
| EKS control-plane logs (CloudWatch) | At rest (customer-managed KMS key, rotation enabled) | SD-4 |
| ElastiCache Redis (legacy-total cache) | At rest + in transit with AUTH | SD-2: TLS enabled, AUTH token from Secrets Manager |

See **Accepted Risks** for the components that are **not** encrypted in this demo (the internal
API-Gateway→ALB→pod hop and the pod→database connection).

## IAM and Least Privilege

- **IRSA (IAM Roles for Service Accounts):** the `transfer-service` service account is backed by a dedicated IAM
  role whose only data permission is `secretsmanager:GetSecretValue` on the **specific** generated DB secret ARN
  (via `grantRead`) — not a wildcard.
- **External Secrets Operator** authenticates to Secrets Manager through that same service account (JWT /
  `serviceAccountRef`); the operator holds no standing AWS credentials of its own.
- The **AWS Load Balancer Controller** is installed with the scoped IAM policy that CDK's `albController`
  provisions.
- EKS cluster, node group, and kubectl-handler roles use the standard AWS-managed EKS policies.

## Secret Management

- **No API keys, passwords, or secrets are stored in code, configuration, or git.** The database password is
  **generated** by Secrets Manager at deploy time.
- Credentials reach the application through **Secrets Manager → External Secrets Operator (IRSA) → Kubernetes
  Secret `db-credentials` → environment variables** — they never appear in the CDK source or the CloudFormation
  template.
- Cognito **User Pool and App Client IDs are public identifiers** and are safe to expose to clients.
- The repository's `.gitignore` excludes `infra/cdk.context.json` so account-specific context is not committed.

## Failure Handling (fail-closed by design)

- **Fail-closed limit read:** if the legacy daily-total cannot be read (dependency down or circuit open), the
  transfer is **rejected with `503`** rather than risk breaching the limit. The call is wrapped in a Resilience4j
  circuit breaker.
- **Never guess about money:** a core-call timeout leaves the reservation `RESERVED` and returns `202`; capacity is
  released only when a **reconciler** obtains a definitive settlement answer from the core status API.
- **Safety valve:** reserved rows carry a TTL and a hard max-age so a stuck reservation can never lock a customer
  out indefinitely; such cases are surfaced for operator attention rather than silently retried.

## Monitoring and Observability

- **EKS control-plane logging** (`api`, `audit`, `authenticator`) streams to CloudWatch.
- The application exposes `/actuator/health`, `/actuator/metrics`, and `/actuator/prometheus` for liveness/readiness
  probes and metrics scraping.

> See Accepted Risks for the observability gaps in this demo (no API Gateway access logs, no deployed
> metrics scraper, no distributed tracing).

## Accepted Risks and Justifications

| Item | Decision | Rationale / Hardening |
|------|----------|-----------------------|
| Per-customer ownership binding is config-seeded | Accepted (demo) | `sub`→`customerId` is enforced (403 on mismatch, SD-1), but bindings come from configuration and the gateway-set `X-Auth-Sub` header is trusted inside the cluster. Back the binding with a customer datastore and restrict in-cluster callers for production. |
| Internal ALB and VPC-Link hop are plain HTTP | Accepted (demo) | Traffic stays within private subnets. Terminate TLS at the ALB with **ACM** for production. |
| Pod→Aurora / pod→Redis not TLS-enforced | Accepted (demo) | Private, SG-restricted paths. Enforce TLS (and Redis AUTH) for production. |
| ElastiCache encryption | Enabled | At-rest encryption (MF-1) and in-transit encryption + AUTH (SD-2) are both enabled on the single-node replication group. The AUTH token is sourced from Secrets Manager and delivered via the External Secrets Operator. No longer accepted debt. |
| No AWS WAF | Accepted (demo) | Cognito authorizer gates the single route. Add **WAF** and request throttling before a public deployment. |
| No custom domain / mutual TLS | Accepted | Served on the default `execute-api` domain; TLS enforced by default. |
| EKS cluster endpoint is public+private (CDK default) | Accepted (demo) | Restrict to private endpoint access for production. |
| EKS authentication mode `CONFIG_MAP` | Accepted (demo) | Prefer access entries / `API` mode for finer-grained, auditable access. |
| Aurora and Cognito retention | Enabled by default | Aurora uses `RETAIN`, deletion protection, 7-day backups, and DB secret rotation; the Cognito user pool uses `RETAIN` (SD-5). The destructive teardown settings apply only with `-c demoTeardown=true`. |
| No API Gateway access logs / X-Ray tracing | Accepted (demo) | Enable access logging and tracing before production. |
| Single NAT Gateway | Accepted (demo) | Cost tradeoff (availability, not security). Use one NAT per AZ for production. |
| Broad AWS-managed policies on EKS cluster/node roles | Accepted (demo) | Standard CDK `eks.Cluster` defaults. Replace with least-privilege customer-managed policies for production. |
| EKS control-plane log encryption | Enabled | The log group uses a customer-managed KMS key with rotation and a 30-day retention (SD-4). |
| Resource tagging | Enabled | Stack-wide `Owner`, `Environment`, `CostCenter`, and `DataClassification` tags (SD-7). |
| CDK-managed IAM roles keep generated names | Accepted (demo) | The IRSA app role, the only role with a direct data permission, has an explicit name and description (SD-8). The 7 roles that `eks.Cluster` and the CDK custom-resource providers create internally keep generated names; naming them would require overriding CDK internals. |
| ALB access logging not enabled | Accepted (demo) | Enable access logs to an encrypted S3 bucket with a lifecycle policy for production (SD-9). |
| Secrets Manager secrets use AWS-managed keys | Accepted (demo) | Supply a customer-managed KMS key for the DB credential and Redis AUTH token, with rotation, for production (SD-10). |
| Aurora IAM database authentication not enabled | Accepted (demo) | Enable IAM auth so pods authenticate by role rather than a long-lived password (SD-11). |
| Aurora enhanced monitoring not enabled | Accepted (demo) | Enable enhanced monitoring for database-level anomaly visibility (SD-12). |
| MySQL Connector/J is GPLv2-licensed (with FOSS Exception) | Accepted | The Aurora JDBC driver (`com.mysql:mysql-connector-j`, runtime scope) is distributed under GPLv2 with the Universal FOSS Exception — a copyleft licence outside the Apache-2.0/MIT/BSD/ISC set. The FOSS Exception permits use alongside this project's MIT-0 licence. Consumers with stricter licence policies should substitute a permissively licensed driver such as the Apache-2.0 AWS Advanced JDBC Wrapper (SD-13). |

---

## Security Checklist

- [x] Single public route behind a Cognito JWT authorizer; internal/stub endpoints never routed
- [x] Admin-only user creation (no open sign-up); strong password policy; public client with no secret
- [x] All compute and data in private subnets; ALB internal, reached only via VPC Link
- [x] Least-privilege security groups (per port, per source SG)
- [x] IRSA with `GetSecretValue` scoped to the specific DB secret ARN
- [x] DB credentials generated in Secrets Manager and delivered via External Secrets Operator — none in code/git
- [x] Encryption at rest for Aurora and Secrets Manager (KMS); TLS at the public API edge
- [x] Fail-closed limit read; reconcile-don't-guess on timeouts; reservation safety valve
- [x] EKS control-plane logging to CloudWatch
- [x] Per-customer ownership authorization (`sub`→`customerId`, 403 on mismatch; bindings config-seeded)
- [ ] TLS on the internal ALB (ACM) and to the datastores *(to add before production)*
- [x] ElastiCache encryption + AUTH (at-rest MF-1, in-transit + AUTH SD-2)
- [ ] AWS WAF, API Gateway access logs, and X-Ray tracing *(to add before production)*
- [x] Aurora `RETAIN` + backups + deletion protection + secret rotation (default; `demoTeardown` opts out)
