# Backend — Multi-Tenant Device Financing & MDM Platform

Node.js 22 + TypeScript service implementing the platform API, inbound/outbound webhooks and the
BullMQ background workers described in the specification documents:

| Spec document | Implemented in |
| --- | --- |
| PRD & System Architecture (lock lifecycle FSM, dual-channel sync, tenant context middleware) | `src/domain/stateMachine.ts`, `src/middleware/auth.ts`, `src/services/messaging.ts` |
| Multi-tenant branding & ZTE (provisioning payloads, ADMIN_EXTRAS_BUNDLE) | `src/routes/admin.ts` (`provisioning-tokens`), `src/routes/devices.ts` (`enroll`) |
| REST API endpoints & Dynamic Tenant JSON Schema | `src/routes/*`, `src/domain/tenantConfig.ts` |
| PostgreSQL schema & Row-Level Security | `db/migrations/001_schema.sql`, `db/migrations/002_rls.sql`, `src/db/pool.ts` |
| Background workers & webhook processing (BullMQ) | `src/workers/processors.ts`, `src/worker.ts`, `src/routes/webhooks.ts` |

## Architecture

```
            ┌──────────────┐  REST / Webhooks   ┌──────────────────────────────┐
 Lender ───▶│              │───────────────────▶│ Express API  (src/server.ts) │
 Portal ───▶│ API Gateway  │                    │  · tenant context middleware │
 DPC    ───▶│ (Kong / LB)  │                    │  · RLS transaction per request│
            └──────────────┘                    └──────┬───────────────┬───────┘
                                                       │ enqueue       │ SQL (app_runtime, RLS)
                                                ┌──────▼──────┐  ┌─────▼──────────┐
                                                │ Redis       │  │ PostgreSQL 16  │
                                                │ BullMQ,     │  │ shared schema  │
                                                │ mutexes,    │  │ + RLS policies │
                                                │ idempotency │  └─────▲──────────┘
                                                └──────┬──────┘        │
                                                ┌──────▼───────────────┴───────┐
                                                │ Workers (src/worker.ts)      │
                                                │  repayment-webhooks  (50)    │
                                                │  overdue-evaluator   (5)     │
                                                │  device-commands     (20)    │──▶ FCM (high priority)
                                                │  fcm-dispatcher      (100)   │──▶ MQTT (retained, fallback)
                                                │  webhook-delivery    (25)    │──▶ Lender webhooks (HMAC)
                                                │  heartbeat-reconciliation    │
                                                └──────────────────────────────┘
```

### Lock lifecycle (deterministic FSM)

`PENDING_ENROLLMENT → ACTIVE → GRACE → SOFT_LOCKED → HARD_LOCKED`, any non-terminal state `→ RELEASED`.
Tamper detection and offline-threshold breaches always go to `HARD_LOCKED`; `UNLOCK` returns to `ACTIVE`.
The overdue evaluator only ever **escalates** (`isEscalation`) based on the tenant's
`warning_overlay_hours_before_lock`, `grace_period_hours` and `soft_lock_hours`.

### Safety guarantees

* **RLS everywhere** — every request/worker transaction runs `set_config('app.current_tenant_id', …, true)`
  (= `SET LOCAL`, PgBouncer-safe) as the unprivileged `app_runtime` role. Composite `(tenant_id, id)` keys
  and composite FKs make cross-tenant references impossible.
* **Global IMEI uniqueness** via the `check_imei_availability()` `SECURITY DEFINER` function.
* **Idempotency** — Redis `SET NX` keys on inbound webhooks, DB unique `(tenant_id, external_reference)` on
  the ledger, deterministic BullMQ job ids for device commands.
* **Per-device Redis mutex** (`lock:device:{id}`) around every state mutation, released with an owner check.
* **Kill-switch** — `tenant:status:{tenantId} = SUSPENDED` makes every worker exit without changing state.
* **Dead-letter queue** — jobs that exhaust retries are parked on `dlq-dead-letter-queue` with an alert log.
* **Key prefixing** — Redis keys / MQTT topics are prefixed with the tenant id (`tenants/{tenant}/devices/{device}/commands`).

## Running locally

```bash
cp .env.example .env
docker compose up -d postgres redis mosquitto
npm install
npm run migrate        # runs as the schema owner (DATABASE_ADMIN_URL)
npm run dev            # API on :8080
npm run dev:worker     # BullMQ workers + cron schedulers
npm run seed           # demo tenant, borrower, loan and a provisioning QR payload
```

Or the whole stack: `docker compose up --build`.

### Tests

```bash
npm test
```

Unit tests cover the FSM, delinquency evaluation, tenant config schema and offline unlock codes.
`tests/integration.test.ts` runs the full lifecycle against real PostgreSQL + Redis (skipped automatically if
they are unreachable): POS registration → enrollment → config fetch → RLS isolation → lock + ACK →
offline code → signed payment webhook (idempotent) → progressive overdue escalation → tamper lock →
heartbeat reconciliation → kill-switch → stats/CSV export → bulk import → branding update → payoff & release.

## API reference

All bodies are JSON. Errors: `{"error": {"code", "message", "details?"}}`.

### Device (DPC) endpoints

| Method | Route | Auth | Description |
| --- | --- | --- | --- |
| POST | `/api/v1/devices/enroll` | Provisioning token (in body) | Enroll hardware; returns `device_jwt`, offline unlock secret, endpoints, MQTT topic |
| GET | `/api/v1/tenants/{tenant_id}/config` | Device JWT (or provisioning token) | Dynamic tenant configuration (ETag / `If-None-Match`) |
| POST | `/api/v1/devices/{device_id}/heartbeat` | Device JWT | Telemetry (battery, location, integrity), command ACKs, returns desired state + loan summary |
| POST | `/api/v1/devices/{device_id}/commands/{command_id}/ack` | Device JWT | Confirms a lock/unlock command was applied |

### Lender / portal endpoints (`Authorization: Bearer <admin token>`)

| Method | Route | Roles | Description |
| --- | --- | --- | --- |
| POST | `/api/v1/admin/auth/login` | — | `{tenant_slug, email, password}` → admin JWT |
| POST | `/api/v1/admin/users` | admin | Create portal user (`admin`, `credit_manager`, `operator`, `agent`) |
| GET/PUT | `/api/v1/admin/tenants/{tenant_id}/branding` | all / admin | Read / partially update tenant config (version bump) |
| POST | `/api/v1/admin/tenants/{tenant_id}/provisioning-tokens` | all | Provisioning token + Android Enterprise QR payload + ZTE DPC extras |
| GET | `/api/v1/admin/fleet/stats` | all | Active / Grace / Soft / Hard / Tampered / Offline counts, loan totals |
| GET | `/api/v1/admin/devices` | all | List devices (`state`, `q`, `tampered`, `limit`, `offset`) |
| GET | `/api/v1/admin/devices/{id}` | all | Device detail, loan summary, last 50 lock events |
| POST | `/api/v1/admin/devices/{id}/{lock,unlock,warn,soft-lock,release}` | admin, credit_manager, operator | Queue a lifecycle command |
| POST | `/api/v1/devices/{id}/{lock,unlock,warn,soft-lock,release}` | same | External REST API aliases |
| GET | `/api/v1/devices/{id}/state` | all | State query |
| GET | `/api/v1/admin/devices/{id}/offline-unlock-code` | admin, credit_manager, operator | 8-digit time-based offline unlock code |
| POST/GET | `/api/v1/admin/borrowers` | all | Create / search borrowers |
| POST/GET | `/api/v1/admin/loans` | all | Book a loan against an IMEI (registers device as `PENDING_ENROLLMENT`) |
| POST | `/api/v1/admin/loans/{id}/repayments` | admin, credit_manager, operator | Manual repayment entry |
| GET/POST/DELETE | `/api/v1/admin/webhooks` | admin | Outbound webhook subscriptions |
| GET | `/api/v1/admin/export/{devices,loans,audit}?format=csv\|json` | admin, credit_manager | Bulk export |
| POST | `/api/v1/admin/import/loans` | admin, credit_manager | Bulk CSV/JSON import of borrowers + IMEIs + loan contracts (per-row results) |

### Inbound webhooks (HMAC-SHA256 of the raw body with the tenant's `inbound_webhook_secret`)

| Method | Route | Signature header | Body |
| --- | --- | --- | --- |
| POST | `/api/v1/webhooks/lenders/{tenant_id}/lock-state` | `x-webhook-signature` | `{action: LOCK\|UNLOCK\|WARN\|SOFT_LOCK\|RELEASE, device_id\|imei\|account_ref, reason?, idempotency_key?}` |
| POST | `/api/v1/webhooks/payments/{tenant_id}` | `x-provider-signature` | `{transactionId, loanId\|accountRef, amount, idempotencyKey?, paymentMethod?}` |

### Outbound webhooks

Events: `device.enrolled`, `device.tamper_detected`, `device.locked`, `device.unlocked`,
`device.heartbeat_lost`, `device.state_changed`, `device.released`, `repayment.received`.
Each POST carries `x-platform-event`, `x-platform-event-id`, `x-platform-timestamp` and
`x-platform-signature = hex(HMAC_SHA256(secret, "<timestamp>.<body>"))`. 5xx/429 responses are retried with
exponential backoff (8 attempts).

### Platform super-admin (`X-Platform-Key`)

| Method | Route | Description |
| --- | --- | --- |
| POST | `/api/v1/platform/tenants` | Provision tenant + config + first admin in one call (returns inbound webhook secret) |
| GET | `/api/v1/platform/tenants` | List tenants |
| PATCH | `/api/v1/platform/tenants/{id}` | Suspend / reactivate (kill-switch), custom domain |

## Production notes

* Put Kong (or another gateway) in front for dynamic TLS per custom domain; the app additionally validates the
  `Host` header against `tenant_domain:{tenant_id}`.
* Configure `GOOGLE_APPLICATION_CREDENTIALS` for real FCM delivery (otherwise pushes are logged — MQTT and
  heartbeat polling still deliver commands).
* Use TLS + per-device mTLS certificates and topic ACLs on the MQTT broker (see `deploy/mosquitto.conf`).
* Google Zero-Touch Customer API calls (`configurations.create`, `devices.applyConfiguration`,
  `devices.unclaim`) use the `zte_dpc_extras` string returned by the provisioning endpoint.
