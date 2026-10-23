# Infrastructure — cross-system limit enforcement (demo)

AWS CDK (TypeScript) that deploys the Spring Boot transfer service on **Amazon EKS**,
fronted by **API Gateway**, backed by **Aurora MySQL** and **ElastiCache Redis**, with
a **mock legacy core** standing in for the on-prem mainframe.

```
client ──HTTPS──▶ API Gateway (HTTP API, Cognito JWT auth)
        ──VPC Link──▶ internal ALB ──▶ EKS pods (transfer-service :8080)
                                         │
                    ┌────────────────────┼───────────────────────┐
                    ▼                     ▼                        ▼
          Aurora MySQL (ledger)   ElastiCache Redis        mock-legacy pod
          Secrets Manager ──ESO──▶ db-credentials          (namespace: legacy)
```

Everything is one stack, one `cdk deploy`. Container images for both the app and the
mock are built and pushed to ECR automatically as part of the deploy.

## What gets created

| Group | Resources |
|---|---|
| Network | VPC (2 AZ, 1 NAT), public + private subnets |
| Compute | EKS 1.30 cluster, managed node group (2× t3.medium), AWS Load Balancer Controller |
| Data | Aurora MySQL Serverless v2 (0.5–2 ACU), ElastiCache Redis (`cache.t4g.micro`) |
| Secrets | Secrets Manager (DB creds) synced into the cluster by External Secrets Operator via IRSA |
| Edge | API Gateway HTTP API + VPC Link → internal ALB, Cognito JWT authorizer |
| Workloads | `transfer-service` Deployment/Service, reconciler `CronJob`, `mock-legacy` Deployment/Service |

Only `POST /api/transfers` is exposed through the gateway. `/internal/*` (the reconciler
trigger) and `/stub/*` are never routed, so they are unreachable from the internet.

## Prerequisites

- **Node.js >= 20** (the installed CDK requires it; Node 18 only synthesizes).
- **Docker running** (image assets are built locally during deploy).
- **AWS credentials** for the target account, and the account/region **bootstrapped**:
  ```bash
  npx cdk bootstrap aws://<ACCOUNT_ID>/us-east-1
  ```
- `kubectl` and `aws` CLI for post-deploy inspection.

## Deploy

```bash
cd infra
npm install

# Uses the existing GateShift Cognito pool by default. Override if needed:
#   -c cognitoUserPoolId=... -c cognitoClientId=... -c cognitoRegion=...
npx cdk deploy
```

Deploy takes ~20–25 min (most of it is the EKS control plane and node group).

On success the stack prints outputs including `TransfersUrl`, `ClusterName`, and a
`KubeconfigCommand`.

## Verify the cluster

```bash
aws eks update-kubeconfig --name <ClusterName> --region us-east-1

kubectl get pods -n app
kubectl get pods -n legacy
kubectl get externalsecret,secret -n app          # db-credentials should be Ready/present
kubectl get targetgroupbindings -n app
kubectl logs -n app deploy/transfer-service        # Flyway migration + startup
```

## Exercise the flow

Get a Cognito access token for the app client, then call the gateway:

```bash
TOKEN=<cognito access/id token>
curl -sS -X POST "$(aws cloudformation describe-stacks \
  --stack-name LimitEnforcementStack \
  --query "Stacks[0].Outputs[?OutputKey=='TransfersUrl'].OutputValue" --output text)" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"transferId":"t-1","customerId":"c1","amount":100.00,"currency":"USD"}'
```

Expected: `200` COMMITTED, `202` PENDING_RECONCILIATION, `422` LIMIT_EXCEEDED,
`502` REJECTED_BY_CORE, or `503` if the legacy total read is unavailable (fail-closed).

### Arrange demo scenarios on the mock legacy core

The mock has a control surface. Reach it in-cluster (it is intentionally not public):

```bash
kubectl -n legacy port-forward svc/mock-legacy 8081:8080 &

# seed a customer's legacy daily total
curl -X POST localhost:8081/control/total \
  -H 'Content-Type: application/json' -d '{"customerId":"c1","total":49999900.00}'

# choose the next core execution outcome: SUCCESS | FAILURE | TIMEOUT
curl -X POST localhost:8081/control/next-result \
  -H 'Content-Type: application/json' -d '{"result":"TIMEOUT"}'

# force the legacy total API down (drives fail-closed / 503)
curl -X POST localhost:8081/control/total-unavailable \
  -H 'Content-Type: application/json' -d '{"unavailable":"true"}'

curl -X POST localhost:8081/control/reset
```

The reconciler `CronJob` runs every minute and drives timed-out `RESERVED` rows to a
definitive state by calling `POST /internal/reconcile` in-cluster.

## Configuration knobs

The stack provisions its **own** Cognito user pool and app client — nothing is reused. After
deploy, read `UserPoolId` and `UserPoolClientId` from the stack outputs; the API Gateway JWT
authorizer is wired to them automatically.

App runtime configuration is injected as env vars in the Deployment
(`lib/limit-enforcement-stack.ts`): `DB_URL`, `DB_USERNAME`/`DB_PASSWORD` (from the
synced `db-credentials` secret), `REDIS_HOST`, `LEGACY_*_URL`,
`LIMIT_ENFORCEMENT_RECONCILER_ENABLED=false`.

## Tear down

```bash
npx cdk destroy
```

By default Aurora and the Cognito user pool use `RemovalPolicy.RETAIN` (Aurora also has
deletion protection and backups), so `cdk destroy` leaves them in place. For a full demo
teardown, run `npx cdk deploy -c demoTeardown=true` first, then
`npx cdk destroy -c demoTeardown=true`. The ECR image assets created by CDK bootstrap
remain in the bootstrap bucket/repo.

## Cost note

EKS control plane (~$0.10/hr) + 2 nodes + Aurora Serverless v2 + ElastiCache + 1 NAT
gateway. Roughly a few dollars per day while running — destroy when idle.

## Security posture (demo vs. production)

Already in place:
- API is Cognito-JWT protected at the edge; internal endpoints are not routed.
- Data stores are in private subnets, reachable only from the cluster security group.
- DB credentials are generated, stored in Secrets Manager, and delivered via IRSA + ESO
  (no static secrets in git or the CloudFormation template).
- Aurora storage encryption enabled, with `RETAIN`, deletion protection, 7-day backups,
  and DB secret rotation by default.

Harden before anything real:
- Put **AWS WAF** in front of the API and enable throttling/usage limits.
- Terminate TLS at the ALB with **ACM** (this demo uses the API Gateway HTTPS edge and
  plain HTTP on the internal ALB only).
- Add **NetworkPolicies**, restrict egress, and scope the mock into a separate account or
  behind Direct Connect/VPN to represent the real on-prem boundary.
- Back the principal-to-customer binding (`principalCustomerMap`) with a customer datastore.
