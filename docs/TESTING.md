# Testing Guide — Cross-System Limit Enforcement

How to deploy and exercise the transfer service end-to-end, using **curl** or **Postman**.

There are two ways to test:

- **Mode A — Local (no AWS, no auth):** run the app on your machine with an in-memory database
  and an in-process stub of the legacy core. Fastest way to see every outcome. No token needed.
- **Mode B — Deployed on AWS:** hit the real API Gateway endpoint with a Cognito JWT, backed by
  EKS + Aurora + ElastiCache and the mock legacy core running in the cluster.

The public contract is identical in both modes:

**`POST /api/transfers`**

Request body:

```json
{
  "transferId": "t-1",     // idempotency key, <= 64 chars
  "customerId": "c1",      // <= 32 chars
  "amount": 100.00,        // > 0, minimum 0.01
  "currency": "USD"        // <= 8 chars
}
```

Response body:

```json
{
  "transferId": "t-1",
  "reservationId": "b0c1...",
  "outcome": "COMMITTED",
  "amount": 100.00,
  "currency": "USD"
}
```

### Outcome → HTTP status

| Outcome | HTTP | Meaning |
|---|---|---|
| `COMMITTED` | `200` | Within the limit and the core confirmed settlement |
| `PENDING_RECONCILIATION` | `202` | Core call timed out; reservation held until the reconciler decides |
| `LIMIT_EXCEEDED` | `422` | The composed daily total would breach the limit |
| `REJECTED_BY_CORE` | `502` | The core rejected the transfer; capacity released |
| *(validation error)* | `400` | Missing/invalid field (e.g. non-positive amount) |
| *(limit read unavailable)* | `503` | Legacy total read failed; **fail-closed** rather than risk a breach |
| *(missing/invalid token, Mode B)* | `401` | API Gateway JWT authorizer rejected the request |

---

## Mode A — Local testing (no AWS)

### 1. Start the app with the stub legacy core

```bash
mvn spring-boot:run "-Dspring-boot.run.profiles=local,stub-legacy"
```

This uses an in-memory H2 database and an in-process stub core you control over HTTP at
`/stub/legacy/*`. The default daily limit is **50,000,000.00**.

Base URL: `http://localhost:8080`

### 2. Walk through every outcome

Each scenario below is self-contained. Reset the stub between scenarios if needed:

```bash
curl -X POST localhost:8080/stub/legacy/reset
```

**Within limit → `200 COMMITTED`**

```bash
curl -i -X POST localhost:8080/api/transfers \
  -H 'Content-Type: application/json' \
  -d '{"transferId":"t-1","customerId":"c1","amount":100.00,"currency":"USD"}'
```

**Composed total exceeds the limit → `422 LIMIT_EXCEEDED`**

```bash
# Seed a near-limit legacy total for the customer
curl -X POST localhost:8080/stub/legacy/total \
  -H 'Content-Type: application/json' -d '{"customerId":"c2","total":"49999950.00"}'

# This 100.00 transfer pushes the total over 50,000,000 → rejected
curl -i -X POST localhost:8080/api/transfers \
  -H 'Content-Type: application/json' \
  -d '{"transferId":"t-2","customerId":"c2","amount":100.00,"currency":"USD"}'
```

**Core rejects → `502 REJECTED_BY_CORE`**

```bash
curl -X POST localhost:8080/stub/legacy/next-result \
  -H 'Content-Type: application/json' -d '{"result":"FAILURE"}'

curl -i -X POST localhost:8080/api/transfers \
  -H 'Content-Type: application/json' \
  -d '{"transferId":"t-3","customerId":"c3","amount":100.00,"currency":"USD"}'
```

**Core times out → `202 PENDING_RECONCILIATION`, then reconcile**

```bash
curl -X POST localhost:8080/stub/legacy/next-result \
  -H 'Content-Type: application/json' -d '{"result":"TIMEOUT"}'

# Returns 202; the reservation is held
curl -i -X POST localhost:8080/api/transfers \
  -H 'Content-Type: application/json' \
  -d '{"transferId":"t-4","customerId":"c4","amount":100.00,"currency":"USD"}'

# Tell the stub the transfer actually settled, then reconcile
curl -X POST localhost:8080/stub/legacy/settlement \
  -H 'Content-Type: application/json' -d '{"transferId":"t-4","status":"SETTLED"}'

curl -i -X POST localhost:8080/internal/reconcile   # -> {"processed":1}
```

**Limit read unavailable → `503` (fail-closed)**

```bash
curl -X POST localhost:8080/stub/legacy/total-unavailable \
  -H 'Content-Type: application/json' -d '{"unavailable":"true"}'

curl -i -X POST localhost:8080/api/transfers \
  -H 'Content-Type: application/json' \
  -d '{"transferId":"t-5","customerId":"c5","amount":100.00,"currency":"USD"}'

# turn it back on afterwards
curl -X POST localhost:8080/stub/legacy/total-unavailable \
  -H 'Content-Type: application/json' -d '{"unavailable":"false"}'
```

**Idempotency — re-posting the same `transferId` returns the original result**

```bash
# Re-run the first request verbatim; you get the same reservationId/outcome, no double count
curl -i -X POST localhost:8080/api/transfers \
  -H 'Content-Type: application/json' \
  -d '{"transferId":"t-1","customerId":"c1","amount":100.00,"currency":"USD"}'
```

**Validation error → `400`**

```bash
curl -i -X POST localhost:8080/api/transfers \
  -H 'Content-Type: application/json' \
  -d '{"transferId":"t-6","customerId":"c6","amount":-5,"currency":"USD"}'
```

---

## Mode B — Deployed on AWS

### 1. Deploy

Follow the [main README](../README.md#deploy-to-aws): configure credentials (`aws configure`),
`cdk bootstrap`, then `cdk deploy`. Capture the stack outputs:

```bash
API=$(aws cloudformation describe-stacks --stack-name LimitEnforcementStack \
  --query "Stacks[0].Outputs[?OutputKey=='TransfersUrl'].OutputValue" --output text)
echo "$API"
# e.g. https://abc123.execute-api.us-east-1.amazonaws.com/api/transfers
```

### 2. Get a Cognito token

The API Gateway JWT authorizer validates the token's audience against the app client ID, so send the
**ID token** as `Authorization: Bearer <token>`.

First make sure the user has a permanent password (a new admin-created user is in
`FORCE_CHANGE_PASSWORD` and cannot log in until this is set):

```bash
POOL_ID=<your-user-pool-id>     # from the UserPoolId stack output
CLIENT_ID=<your-app-client-id>  # from the UserPoolClientId stack output

aws cognito-idp admin-set-user-password \
  --user-pool-id "$POOL_ID" \
  --username you@example.com \
  --password '<StrongPassword>' --permanent --region us-east-1
```

**Option 1 — `USER_PASSWORD_AUTH`** (requires `ALLOW_USER_PASSWORD_AUTH` on the app client):

```bash
ID_TOKEN=$(aws cognito-idp initiate-auth \
  --auth-flow USER_PASSWORD_AUTH \
  --client-id "$CLIENT_ID" \
  --auth-parameters USERNAME=you@example.com,PASSWORD='<StrongPassword>' \
  --region us-east-1 \
  --query 'AuthenticationResult.IdToken' --output text)
```

**Option 2 — `ADMIN_USER_PASSWORD_AUTH`** (server-side flow; requires `ALLOW_ADMIN_USER_PASSWORD_AUTH`):

```bash
ID_TOKEN=$(aws cognito-idp admin-initiate-auth \
  --user-pool-id "$POOL_ID" \
  --client-id "$CLIENT_ID" \
  --auth-flow ADMIN_USER_PASSWORD_AUTH \
  --auth-parameters USERNAME=you@example.com,PASSWORD='<StrongPassword>' \
  --region us-east-1 \
  --query 'AuthenticationResult.IdToken' --output text)

echo "${ID_TOKEN:0:20}..."   # sanity check it's populated
```

### 3. Call the API

```bash
curl -i -X POST "$API" \
  -H "Authorization: Bearer $ID_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"transferId":"t-100","customerId":"c1","amount":100.00,"currency":"USD"}'
```

Without a token (or with an invalid one) you get `401` from API Gateway — confirming auth is enforced.

### 4. Arrange scenarios on the in-cluster mock core

The mock's control surface is intentionally **not** exposed through the gateway. Reach it with a
port-forward, then use the same `/control/*` calls:

```bash
aws eks update-kubeconfig --name <ClusterName> --region us-east-1
kubectl -n legacy port-forward svc/mock-legacy 8081:8080 &

# near-limit total → next transfer for c9 should return 422
curl -X POST localhost:8081/control/total \
  -H 'Content-Type: application/json' -d '{"customerId":"c9","total":49999950.00}'

# next core outcome
curl -X POST localhost:8081/control/next-result \
  -H 'Content-Type: application/json' -d '{"result":"TIMEOUT"}'

# fail-closed
curl -X POST localhost:8081/control/total-unavailable \
  -H 'Content-Type: application/json' -d '{"unavailable":"true"}'

curl -X POST localhost:8081/control/reset
```

Note the control-surface field names differ slightly from the local stub (`/control/total` vs
`/stub/legacy/total`) but behave the same.

### 5. Reconciliation

The reconciler runs as a CronJob every minute. To force it immediately:

```bash
kubectl create job --from=cronjob/reconciler -n app reconcile-now
kubectl logs -n app job/reconcile-now
```

---

## Postman

### Import an environment

Create a Postman **Environment** with these variables:

| Variable | Local value | AWS value |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | `https://<api-id>.execute-api.us-east-1.amazonaws.com` |
| `transfersPath` | `/api/transfers` | `/api/transfers` |
| `cognitoRegion` | *(blank)* | `us-east-1` |
| `clientId` | *(blank)* | `<your-app-client-id>` |
| `username` | *(blank)* | `you@example.com` |
| `password` | *(blank)* | `<StrongPassword>` |
| `idToken` | *(blank — auto-filled)* | *(blank — auto-filled)* |

### Request 1 — Get Cognito Token (AWS only)

Cognito's `InitiateAuth` is a plain HTTPS call (no request signing), so Postman can fetch the token
directly. Add this request and it stores the ID token into the environment for the next call.

- **Method/URL:** `POST https://cognito-idp.{{cognitoRegion}}.amazonaws.com/`
- **Headers:**
  - `Content-Type: application/x-amz-json-1.1`
  - `X-Amz-Target: AWSCognitoIdentityProviderService.InitiateAuth`
- **Body (raw JSON):**

```json
{
  "AuthFlow": "USER_PASSWORD_AUTH",
  "ClientId": "{{clientId}}",
  "AuthParameters": { "USERNAME": "{{username}}", "PASSWORD": "{{password}}" }
}
```

- **Tests tab (saves the token):**

```javascript
const json = pm.response.json();
if (json.AuthenticationResult && json.AuthenticationResult.IdToken) {
  pm.environment.set("idToken", json.AuthenticationResult.IdToken);
  pm.test("Got ID token", () => pm.expect(json.AuthenticationResult.IdToken).to.be.a("string"));
} else {
  pm.test("Auth returned a challenge or error", () => pm.expect.fail(JSON.stringify(json)));
}
```

> If this returns a `ChallengeName` of `NEW_PASSWORD_REQUIRED`, set a permanent password first (see
> Mode B step 2). If it errors that the auth flow isn't enabled, use the CLI `admin-initiate-auth`
> flow and paste the token into `idToken` manually.

### Request 2 — Create Transfer

- **Method/URL:** `POST {{baseUrl}}{{transfersPath}}`
- **Authorization tab:** Type **Bearer Token**, Token = `{{idToken}}`
  *(leave this blank for local Mode A — the local app has no auth)*
- **Headers:** `Content-Type: application/json`
- **Body (raw JSON):**

```json
{
  "transferId": "t-{{$timestamp}}",
  "customerId": "c1",
  "amount": 100.00,
  "currency": "USD"
}
```

- **Tests tab (optional assertions):**

```javascript
pm.test("status is a known outcome", () =>
  pm.expect([200, 202, 422, 502, 503]).to.include(pm.response.code));
if (pm.response.code === 200) {
  pm.test("committed", () => pm.expect(pm.response.json().outcome).to.eql("COMMITTED"));
}
```

Run **Get Cognito Token** once (AWS mode), then run **Create Transfer** repeatedly. The `{{$timestamp}}`
in `transferId` keeps each request unique; reuse a fixed id to test idempotency.

### Optional — control-surface requests

For scenario setup, add requests pointing at the mock control surface:

- Local: `POST {{baseUrl}}/stub/legacy/total`, `/stub/legacy/next-result`, `/stub/legacy/total-unavailable`, `/stub/legacy/reset`
- AWS: port-forward `svc/mock-legacy` (Mode B step 4) and point a second environment's `baseUrl` at `http://localhost:8081` with `/control/...` paths.

---

## Expected results at a glance

| Scenario | Setup | Expected |
|---|---|---|
| Happy path | fresh customer | `200 COMMITTED` |
| Over limit | legacy total near 50M | `422 LIMIT_EXCEEDED` |
| Core rejects | `next-result = FAILURE` | `502 REJECTED_BY_CORE` |
| Core timeout | `next-result = TIMEOUT` | `202 PENDING_RECONCILIATION` → reconcile to `COMMITTED`/`EXPIRED` |
| Limit read down | `total-unavailable = true` | `503` fail-closed |
| Bad input | `amount <= 0` | `400` |
| No token (AWS) | omit `Authorization` | `401` |
| Idempotent repost | same `transferId` | same `reservationId` + outcome |

---

## Troubleshooting

- **`401` on every AWS call** — the token is missing, expired (default ~1h), or its audience doesn't
  match `clientId`. Re-run **Get Cognito Token** and confirm you're sending the **ID** token.
- **`InitiateAuth` says the flow is disabled** — the app client doesn't allow `USER_PASSWORD_AUTH`.
  Use the CLI `admin-initiate-auth` (`ADMIN_USER_PASSWORD_AUTH`) instead, or enable the flow on the client.
- **`NEW_PASSWORD_REQUIRED` challenge** — the user is in `FORCE_CHANGE_PASSWORD`; set a permanent
  password with `admin-set-user-password --permanent`.
- **Every transfer returns `503`** — the legacy total read is failing. Check the `mock-legacy` pod and
  whether `total-unavailable` was left on; `curl .../control/reset`.
- **`202` never becomes final** — trigger the reconciler (Mode B step 5) or wait for the next CronJob run,
  and make sure the transfer's settlement status is set on the mock.
- **Connection refused (local)** — the app isn't up yet; Spring Boot logs `Started LimitEnforcementApplication`
  when ready. Flyway runs migrations on startup.
