-- =====================================================================
-- 001_schema.sql
-- Shared-database / shared-schema multi-tenant model.
-- Every tenant-scoped table uses the composite primary key (tenant_id, id)
-- and every child FK references (tenant_id, <parent_id>) so cross-tenant
-- references are impossible at the database level.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1. TENANTS (Global control table — not RLS protected)
CREATE TABLE IF NOT EXISTS tenants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(63)  NOT NULL UNIQUE,
    status          VARCHAR(32)  NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active', 'suspended', 'deactivated')),
    custom_domain   VARCHAR(255) UNIQUE,
    -- Secret used to verify inbound lender / payment-provider webhooks (HMAC-SHA256)
    inbound_webhook_secret VARCHAR(128) NOT NULL DEFAULT encode(gen_random_bytes(32), 'hex'),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. TENANT CONFIGURATION (branding + policies — DynamicTenantConfiguration JSON)
CREATE TABLE IF NOT EXISTS tenant_configs (
    tenant_id   UUID PRIMARY KEY,
    version     INTEGER NOT NULL DEFAULT 1,
    config      JSONB   NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_tenant_configs_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);

-- 3. TENANT USERS (Lender admins, credit managers, POS agents)
CREATE TABLE IF NOT EXISTS tenant_users (
    tenant_id     UUID NOT NULL,
    id            UUID DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(64)  NOT NULL DEFAULT 'operator'
                  CHECK (role IN ('admin', 'credit_manager', 'operator', 'agent')),
    is_active     BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT fk_tenant_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT uq_tenant_user_email UNIQUE (tenant_id, email)
);

-- 4. BORROWERS
CREATE TABLE IF NOT EXISTS borrowers (
    tenant_id    UUID NOT NULL,
    id           UUID DEFAULT gen_random_uuid(),
    national_id  VARCHAR(64)  NOT NULL,
    first_name   VARCHAR(128) NOT NULL,
    last_name    VARCHAR(128) NOT NULL,
    email        VARCHAR(255),
    phone_number VARCHAR(32)  NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT fk_borrowers_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT uq_borrower_national_id UNIQUE (tenant_id, national_id)
);

-- 5. DEVICES
CREATE TABLE IF NOT EXISTS devices (
    tenant_id        UUID NOT NULL,
    id               UUID DEFAULT gen_random_uuid(),
    borrower_id      UUID,                         -- attached at POS / via bulk import
    imei             VARCHAR(18) NOT NULL UNIQUE,  -- globally unique hardware lock
    imei_2           VARCHAR(18),
    serial_number    VARCHAR(128),
    mac_address      VARCHAR(32),
    manufacturer     VARCHAR(128),
    model            VARCHAR(128) NOT NULL DEFAULT 'UNKNOWN',
    android_version  VARCHAR(16),
    api_level        INTEGER,
    build_fingerprint VARCHAR(255),
    knox_sdk_version VARCHAR(16),
    -- Lifecycle FSM state (PENDING_ENROLLMENT, ACTIVE, GRACE, SOFT_LOCKED, HARD_LOCKED, RELEASED)
    state            VARCHAR(32) NOT NULL DEFAULT 'PENDING_ENROLLMENT'
                     CHECK (state IN ('PENDING_ENROLLMENT', 'ACTIVE', 'GRACE', 'SOFT_LOCKED', 'HARD_LOCKED', 'RELEASED')),
    -- Physical lock status as acknowledged by the device
    lock_status      VARCHAR(32) NOT NULL DEFAULT 'unlocked'
                     CHECK (lock_status IN ('unlocked', 'pending_lock', 'locked', 'pending_unlock')),
    is_tampered      BOOLEAN NOT NULL DEFAULT false,
    tamper_details   JSONB,
    fcm_token        TEXT,
    offline_secret   VARCHAR(128) NOT NULL DEFAULT encode(gen_random_bytes(32), 'hex'),
    battery_level    INTEGER,
    last_location    JSONB,
    reported_state   VARCHAR(32),
    state_mismatch_count INTEGER NOT NULL DEFAULT 0,
    last_heartbeat_at TIMESTAMPTZ,
    heartbeat_lost_notified BOOLEAN NOT NULL DEFAULT false,
    last_command_at  TIMESTAMPTZ,
    enrolled_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT fk_devices_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT fk_devices_borrower FOREIGN KEY (tenant_id, borrower_id) REFERENCES borrowers(tenant_id, id) ON DELETE RESTRICT
);

-- 6. LOANS
CREATE TABLE IF NOT EXISTS loans (
    tenant_id           UUID NOT NULL,
    id                  UUID DEFAULT gen_random_uuid(),
    account_ref         VARCHAR(64) NOT NULL,       -- human readable, e.g. LND-894120
    borrower_id         UUID NOT NULL,
    device_id           UUID NOT NULL,
    principal_amount    NUMERIC(12, 2) NOT NULL CHECK (principal_amount > 0),
    outstanding_balance NUMERIC(12, 2) NOT NULL CHECK (outstanding_balance >= 0),
    installment_amount  NUMERIC(12, 2) NOT NULL CHECK (installment_amount > 0),
    installment_period_days INTEGER NOT NULL DEFAULT 30 CHECK (installment_period_days > 0),
    amount_due          NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (amount_due >= 0),
    next_due_at         TIMESTAMPTZ NOT NULL,
    currency            VARCHAR(3) NOT NULL DEFAULT 'USD',
    status              VARCHAR(32) NOT NULL DEFAULT 'active'
                        CHECK (status IN ('draft', 'active', 'overdue', 'paid_off', 'defaulted')),
    issued_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT uq_loan_account_ref UNIQUE (tenant_id, account_ref),
    CONSTRAINT fk_loans_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT fk_loans_borrower FOREIGN KEY (tenant_id, borrower_id) REFERENCES borrowers(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_loans_device FOREIGN KEY (tenant_id, device_id) REFERENCES devices(tenant_id, id) ON DELETE RESTRICT
);

-- 7. REPAYMENTS (ledger)
CREATE TABLE IF NOT EXISTS repayments (
    tenant_id          UUID NOT NULL,
    id                 UUID DEFAULT gen_random_uuid(),
    loan_id            UUID NOT NULL,
    amount             NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    payment_method     VARCHAR(64) NOT NULL,
    external_reference VARCHAR(128),
    processed_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT uq_repayment_reference UNIQUE (tenant_id, external_reference),
    CONSTRAINT fk_repayments_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT fk_repayments_loan FOREIGN KEY (tenant_id, loan_id) REFERENCES loans(tenant_id, id) ON DELETE RESTRICT
);

-- 8. DEVICE LOCK EVENTS (command + audit log)
CREATE TABLE IF NOT EXISTS device_lock_events (
    tenant_id   UUID NOT NULL,
    id          UUID DEFAULT gen_random_uuid(),
    device_id   UUID NOT NULL,
    event_type  VARCHAR(64) NOT NULL
                CHECK (event_type IN ('LOCK_COMMAND', 'UNLOCK_COMMAND', 'WARN_COMMAND', 'SOFT_LOCK_COMMAND',
                                      'RELEASE_COMMAND', 'HEARTBEAT_ACK', 'TAMPER_ALERT', 'OFFLINE_UNLOCK_ISSUED')),
    status      VARCHAR(32) NOT NULL DEFAULT 'queued'
                CHECK (status IN ('queued', 'sent', 'acknowledged', 'failed')),
    target_state VARCHAR(32),
    reason      VARCHAR(128),
    payload     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    acknowledged_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT fk_lock_events_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT fk_lock_events_device FOREIGN KEY (tenant_id, device_id) REFERENCES devices(tenant_id, id) ON DELETE CASCADE
);

-- 9. OUTBOUND WEBHOOK SUBSCRIPTIONS (lender systems)
CREATE TABLE IF NOT EXISTS webhook_subscriptions (
    tenant_id  UUID NOT NULL,
    id         UUID DEFAULT gen_random_uuid(),
    url        TEXT NOT NULL,
    secret     VARCHAR(128) NOT NULL DEFAULT encode(gen_random_bytes(32), 'hex'),
    events     TEXT[] NOT NULL,
    is_active  BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT fk_webhook_subscriptions_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);

-- INDEXES
CREATE INDEX IF NOT EXISTS idx_tenant_users_lookup ON tenant_users(tenant_id, id);
CREATE INDEX IF NOT EXISTS idx_borrowers_search    ON borrowers(tenant_id, last_name, first_name);
CREATE INDEX IF NOT EXISTS idx_devices_borrower    ON devices(tenant_id, borrower_id);
CREATE INDEX IF NOT EXISTS idx_devices_state       ON devices(tenant_id, state);
CREATE INDEX IF NOT EXISTS idx_devices_heartbeat   ON devices(tenant_id, last_heartbeat_at);
CREATE INDEX IF NOT EXISTS idx_loans_borrower      ON loans(tenant_id, borrower_id);
CREATE INDEX IF NOT EXISTS idx_loans_due           ON loans(tenant_id, status, next_due_at);
CREATE INDEX IF NOT EXISTS idx_repayments_loan     ON repayments(tenant_id, loan_id);
CREATE INDEX IF NOT EXISTS idx_lock_events_device  ON device_lock_events(tenant_id, device_id, created_at DESC);
