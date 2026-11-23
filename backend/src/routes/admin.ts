import { Router, type Request } from 'express';
import bcrypt from 'bcryptjs';
import { z } from 'zod';
import { parse as parseCsv } from 'csv-parse/sync';
import { stringify as toCsv } from 'csv-stringify/sync';
import { config } from '../config.js';
import { withTenant, isUuid } from '../db/pool.js';
import { ah, param } from '../lib/http.js';
import { badRequest, conflict, notFound, unauthorized } from '../lib/errors.js';
import { tokens } from '../lib/jwt.js';
import { requireAdmin } from '../middleware/auth.js';
import { rateLimit } from '../middleware/rateLimit.js';
import { DEVICE_STATES, canTransition, type CommandAction } from '../domain/stateMachine.js';
import { applyBrandingUpdate, brandingUpdateSchema } from '../domain/tenantConfig.js';
import { generateOfflineUnlockCode, offlineWindow } from '../domain/offlineUnlock.js';
import { getLoanSummary } from '../services/deviceService.js';
import { WEBHOOK_EVENTS } from '../services/outboundWebhooks.js';
import { QUEUES, enqueueDeviceCommand, jobId, queue, type RepaymentJob } from '../services/queues.js';
import { getTenantBySlug, invalidateConfigCache, loadConfigTx, saveConfigTx } from '../services/tenantService.js';

export const adminRouter = Router();

const LOCK_ROLES = ['admin', 'credit_manager', 'operator'] as const;
const ALL_ROLES = ['admin', 'credit_manager', 'operator', 'agent'] as const;

const tenantOf = (req: Request) => req.tenantId!;

// ---------------------------------------------------------------- auth

adminRouter.post(
  '/api/v1/admin/auth/login',
  rateLimit('login', 20, 60),
  ah(async (req, res) => {
    const body = z.object({ tenant_slug: z.string(), email: z.string().email(), password: z.string().min(1) }).parse(req.body);
    const tenant = await getTenantBySlug(body.tenant_slug);
    if (!tenant || tenant.status !== 'active') throw unauthorized('Invalid credentials');
    const user = await withTenant(tenant.id, async (tx) =>
      (await tx.query('SELECT * FROM tenant_users WHERE email = $1 AND is_active = true', [body.email.toLowerCase()])).rows[0],
    );
    if (!user || !(await bcrypt.compare(body.password, user.password_hash))) throw unauthorized('Invalid credentials');
    res.json({
      access_token: tokens.signAdmin({ sub: user.id, tenant_id: tenant.id, role: user.role }),
      token_type: 'Bearer',
      expires_in: config.adminJwtTtl,
      tenant: { id: tenant.id, name: tenant.name, slug: tenant.slug },
      user: { id: user.id, email: user.email, role: user.role },
    });
  }),
);

adminRouter.post(
  '/api/v1/admin/users',
  requireAdmin('admin'),
  ah(async (req, res) => {
    const body = z
      .object({ email: z.string().email(), password: z.string().min(10), role: z.enum(ALL_ROLES).default('operator') })
      .parse(req.body);
    const hash = await bcrypt.hash(body.password, 12);
    const row = await withTenant(tenantOf(req), async (tx) => {
      try {
        return (
          await tx.query(
            `INSERT INTO tenant_users (tenant_id, email, password_hash, role) VALUES ($1, $2, $3, $4) RETURNING id, email, role, created_at`,
            [tenantOf(req), body.email.toLowerCase(), hash, body.role],
          )
        ).rows[0];
      } catch (err: any) {
        if (err.code === '23505') throw conflict('User already exists');
        throw err;
      }
    });
    res.status(201).json(row);
  }),
);

// ---------------------------------------------------------------- branding / config

adminRouter.get(
  '/api/v1/admin/tenants/:tenant_id/branding',
  requireAdmin(...ALL_ROLES),
  ah(async (req, res) => {
    res.json(await withTenant(tenantOf(req), (tx) => loadConfigTx(tx, tenantOf(req))));
  }),
);

/** PUT /api/v1/admin/tenants/{tenant_id}/branding — Admin Bearer */
adminRouter.put(
  '/api/v1/admin/tenants/:tenant_id/branding',
  requireAdmin('admin'),
  ah(async (req, res) => {
    const update = brandingUpdateSchema.parse(req.body);
    const tenantId = tenantOf(req);
    const saved = await withTenant(tenantId, async (tx) => {
      const current = await loadConfigTx(tx, tenantId);
      return saveConfigTx(tx, applyBrandingUpdate(current, update));
    });
    await invalidateConfigCache(tenantId);
    res.json(saved);
  }),
);

// ---------------------------------------------------------------- provisioning (QR / ZTE)

adminRouter.post(
  '/api/v1/admin/tenants/:tenant_id/provisioning-tokens',
  requireAdmin(...ALL_ROLES),
  ah(async (req, res) => {
    const { ttl_days, wifi_ssid, wifi_password } = z
      .object({ ttl_days: z.number().int().min(1).max(90).default(7), wifi_ssid: z.string().optional(), wifi_password: z.string().optional() })
      .parse(req.body ?? {});
    const tenantId = tenantOf(req);
    const token = tokens.signProvisioning({ tenant_id: tenantId, jti: crypto.randomUUID() }, `${ttl_days}d`);
    const extras = {
      tenant_id: tenantId,
      provisioning_token: token,
      api_gateway_url: `${config.publicApiUrl.replace(/\/$/, '')}/api/v1`,
    };
    // Android Enterprise 6-tap QR payload
    const qr: Record<string, unknown> = {
      'android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME': config.dpc.adminComponent,
      'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION': config.dpc.downloadUrl,
      'android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM': config.dpc.signatureChecksum,
      'android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED': true,
      'android.app.extra.PROVISIONING_SKIP_ENCRYPTION': false,
      'android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE': extras,
    };
    if (wifi_ssid) {
      qr['android.app.extra.PROVISIONING_WIFI_SSID'] = wifi_ssid;
      if (wifi_password) {
        qr['android.app.extra.PROVISIONING_WIFI_PASSWORD'] = wifi_password;
        qr['android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE'] = 'WPA';
      }
    }
    res.status(201).json({
      provisioning_token: token,
      expires_in_days: ttl_days,
      qr_payload: qr,
      // Single-line JSON for the Zero-Touch portal "DPC extras" field
      zte_dpc_extras: JSON.stringify(extras),
    });
  }),
);

// ---------------------------------------------------------------- fleet analytics

adminRouter.get(
  '/api/v1/admin/fleet/stats',
  requireAdmin(...ALL_ROLES),
  ah(async (req, res) => {
    const stats = await withTenant(tenantOf(req), async (tx) => {
      const byState = await tx.query(`SELECT state, COUNT(*)::int AS count FROM devices GROUP BY state`);
      const flags = await tx.query(
        `SELECT COUNT(*) FILTER (WHERE is_tampered)::int AS tampered,
                COUNT(*) FILTER (WHERE state NOT IN ('PENDING_ENROLLMENT','RELEASED') AND (last_heartbeat_at IS NULL OR last_heartbeat_at < NOW() - INTERVAL '1 hour'))::int AS offline,
                COUNT(*)::int AS total
           FROM devices`,
      );
      const loans = await tx.query(
        `SELECT COUNT(*) FILTER (WHERE status = 'active')::int AS active, COUNT(*) FILTER (WHERE status = 'overdue')::int AS overdue,
                COUNT(*) FILTER (WHERE status = 'paid_off')::int AS paid_off,
                COALESCE(SUM(outstanding_balance) FILTER (WHERE status IN ('active','overdue')), 0)::float AS outstanding_total,
                COALESCE(SUM(amount_due) FILTER (WHERE status = 'overdue'), 0)::float AS overdue_amount
           FROM loans`,
      );
      const repaid = await tx.query(`SELECT COALESCE(SUM(amount), 0)::float AS last_30d FROM repayments WHERE processed_at > NOW() - INTERVAL '30 days'`);
      const states = Object.fromEntries(DEVICE_STATES.map((s) => [s, 0])) as Record<string, number>;
      for (const r of byState.rows) states[r.state] = r.count;
      return {
        devices: {
          total: flags.rows[0].total,
          active: states.ACTIVE,
          grace: states.GRACE,
          soft_locked: states.SOFT_LOCKED,
          hard_locked: states.HARD_LOCKED,
          pending_enrollment: states.PENDING_ENROLLMENT,
          released: states.RELEASED,
          tampered: flags.rows[0].tampered,
          offline: flags.rows[0].offline,
        },
        loans: loans.rows[0],
        repayments: repaid.rows[0],
      };
    });
    res.json(stats);
  }),
);

// ---------------------------------------------------------------- devices

const listQuery = z.object({
  state: z.enum(DEVICE_STATES).optional(),
  q: z.string().optional(),
  tampered: z.enum(['true', 'false']).optional(),
  limit: z.coerce.number().int().min(1).max(500).default(50),
  offset: z.coerce.number().int().min(0).default(0),
});

adminRouter.get(
  '/api/v1/admin/devices',
  requireAdmin(...ALL_ROLES),
  ah(async (req, res) => {
    const q = listQuery.parse(req.query);
    const rows = await withTenant(tenantOf(req), async (tx) =>
      (
        await tx.query(
          `SELECT d.id, d.imei, d.imei_2, d.serial_number, d.model, d.manufacturer, d.state, d.lock_status, d.is_tampered,
                  d.battery_level, d.last_heartbeat_at, d.enrolled_at, d.borrower_id,
                  b.first_name || ' ' || b.last_name AS borrower_name
             FROM devices d LEFT JOIN borrowers b ON b.tenant_id = d.tenant_id AND b.id = d.borrower_id
            WHERE ($1::text IS NULL OR d.state = $1)
              AND ($2::text IS NULL OR d.imei LIKE $2 || '%' OR d.serial_number ILIKE $2 || '%')
              AND ($3::boolean IS NULL OR d.is_tampered = $3)
            ORDER BY d.created_at DESC LIMIT $4 OFFSET $5`,
          [q.state ?? null, q.q ?? null, q.tampered === undefined ? null : q.tampered === 'true', q.limit, q.offset],
        )
      ).rows,
    );
    res.json({ data: rows, limit: q.limit, offset: q.offset });
  }),
);

async function deviceDetail(tenantId: string, deviceId: string) {
  if (!isUuid(deviceId)) throw notFound('Device not found');
  return withTenant(tenantId, async (tx) => {
    const { rows } = await tx.query(
      `SELECT id, imei, imei_2, serial_number, mac_address, manufacturer, model, android_version, api_level, build_fingerprint,
              knox_sdk_version, state, lock_status, is_tampered, tamper_details, battery_level, last_location, reported_state,
              last_heartbeat_at, last_command_at, enrolled_at, borrower_id
         FROM devices WHERE id = $1`,
      [deviceId],
    );
    if (!rows[0]) throw notFound('Device not found');
    const events = await tx.query(
      `SELECT id, event_type, status, target_state, reason, payload, created_at, acknowledged_at
         FROM device_lock_events WHERE device_id = $1 ORDER BY created_at DESC LIMIT 50`,
      [deviceId],
    );
    return { ...rows[0], loan: await getLoanSummary(tx, deviceId), events: events.rows };
  });
}

adminRouter.get(
  '/api/v1/admin/devices/:device_id',
  requireAdmin(...ALL_ROLES),
  ah(async (req, res) => {
    res.json(await deviceDetail(tenantOf(req), param(req, 'device_id')));
  }),
);

/** External lender API alias: GET /api/v1/devices/{device_id}/state */
adminRouter.get(
  '/api/v1/devices/:device_id/state',
  requireAdmin(...ALL_ROLES),
  ah(async (req, res) => {
    const d = await deviceDetail(tenantOf(req), param(req, 'device_id'));
    res.json({ device_id: d.id, state: d.state, lock_status: d.lock_status, is_tampered: d.is_tampered, last_heartbeat_at: d.last_heartbeat_at });
  }),
);

const ACTION_ROUTES: Record<string, CommandAction> = {
  lock: 'LOCK',
  unlock: 'UNLOCK',
  warn: 'WARN',
  'soft-lock': 'SOFT_LOCK',
  release: 'RELEASE',
};

const commandHandler = (action: CommandAction) =>
  ah(async (req, res) => {
    const { reason, idempotency_key } = z
      .object({ reason: z.string().max(128).default('MANUAL_' + action), idempotency_key: z.string().max(128).optional() })
      .parse(req.body ?? {});
    const tenantId = tenantOf(req);
    const deviceId = param(req, 'device_id');
    if (!isUuid(deviceId)) throw notFound('Device not found');
    const device = await withTenant(tenantId, async (tx) => (await tx.query('SELECT id, state FROM devices WHERE id = $1', [deviceId])).rows[0]);
    if (!device) throw notFound('Device not found');
    if (!canTransition(device.state, action)) throw conflict(`Cannot ${action} a device in state ${device.state}`, 'INVALID_STATE_TRANSITION');
    const jobId = await enqueueDeviceCommand(
      { tenantId, deviceId, action, reason, source: req.path.startsWith('/api/v1/admin') ? 'portal' : 'api' },
      idempotency_key,
    );
    res.status(202).json({ status: 'QUEUED', device_id: deviceId, action, job_id: jobId, current_state: device.state });
  });

for (const [route, action] of Object.entries(ACTION_ROUTES)) {
  adminRouter.post(`/api/v1/admin/devices/:device_id/${route}`, requireAdmin(...LOCK_ROLES), rateLimit('commands', 300, 60), commandHandler(action));
  // External REST API aliases (e.g. POST /api/v1/devices/{id}/lock)
  adminRouter.post(`/api/v1/devices/:device_id/${route}`, requireAdmin(...LOCK_ROLES), rateLimit('commands', 300, 60), commandHandler(action));
}

/** Support-desk: issue an offline unlock code the borrower types on the lock screen. */
adminRouter.get(
  '/api/v1/admin/devices/:device_id/offline-unlock-code',
  requireAdmin(...LOCK_ROLES),
  ah(async (req, res) => {
    const tenantId = tenantOf(req);
    const deviceId = param(req, 'device_id');
    if (!isUuid(deviceId)) throw notFound('Device not found');
    const out = await withTenant(tenantId, async (tx) => {
      const d = (await tx.query('SELECT id, offline_secret FROM devices WHERE id = $1', [deviceId])).rows[0];
      if (!d) throw notFound('Device not found');
      const nowSec = Math.floor(Date.now() / 1000);
      const window = offlineWindow(nowSec, config.offlineUnlockWindowSeconds);
      const code = generateOfflineUnlockCode(d.offline_secret, d.id, window);
      await tx.query(
        `INSERT INTO device_lock_events (tenant_id, device_id, event_type, status, reason, payload)
         VALUES ($1, $2, 'OFFLINE_UNLOCK_ISSUED', 'sent', 'SUPPORT_ISSUED', $3)`,
        [tenantId, deviceId, JSON.stringify({ window, issued_by: req.admin!.sub })],
      );
      return { code, window, valid_until: new Date((window + 1) * config.offlineUnlockWindowSeconds * 1000).toISOString() };
    });
    res.json(out);
  }),
);

// ---------------------------------------------------------------- borrowers & loans

const borrowerSchema = z.object({
  national_id: z.string().min(1).max(64),
  first_name: z.string().min(1).max(128),
  last_name: z.string().min(1).max(128),
  email: z.string().email().optional().nullable(),
  phone_number: z.string().min(5).max(32),
});

adminRouter.post(
  '/api/v1/admin/borrowers',
  requireAdmin(...ALL_ROLES),
  ah(async (req, res) => {
    const b = borrowerSchema.parse(req.body);
    const row = await withTenant(tenantOf(req), async (tx) => {
      try {
        return (
          await tx.query(
            `INSERT INTO borrowers (tenant_id, national_id, first_name, last_name, email, phone_number) VALUES ($1,$2,$3,$4,$5,$6) RETURNING *`,
            [tenantOf(req), b.national_id, b.first_name, b.last_name, b.email ?? null, b.phone_number],
          )
        ).rows[0];
      } catch (err: any) {
        if (err.code === '23505') throw conflict('Borrower with this national_id already exists');
        throw err;
      }
    });
    res.status(201).json(row);
  }),
);

adminRouter.get(
  '/api/v1/admin/borrowers',
  requireAdmin(...ALL_ROLES),
  ah(async (req, res) => {
    const { q, limit, offset } = listQuery.pick({ q: true, limit: true, offset: true }).parse(req.query);
    const rows = await withTenant(tenantOf(req), async (tx) =>
      (
        await tx.query(
          `SELECT * FROM borrowers WHERE ($1::text IS NULL OR last_name ILIKE $1 || '%' OR national_id = $1 OR phone_number = $1)
            ORDER BY last_name, first_name LIMIT $2 OFFSET $3`,
          [q ?? null, limit, offset],
        )
      ).rows,
    );
    res.json({ data: rows, limit, offset });
  }),
);

const loanSchema = z.object({
  borrower_id: z.string().refine(isUuid),
  imei: z.string().regex(/^\d{14,17}$/),
  account_ref: z.string().max(64).optional(),
  principal_amount: z.number().positive(),
  installment_amount: z.number().positive(),
  installment_period_days: z.number().int().positive().default(30),
  first_due_at: z.coerce.date(),
  currency: z.string().length(3).default('USD'),
  model: z.string().max(128).optional(),
});

/** Registers a device IMEI at the POS and books the loan against it (device becomes PENDING_ENROLLMENT). */
async function createLoan(tx: import('../db/pool.js').Tx, tenantId: string, l: z.infer<typeof loanSchema>) {
  const avail = (await tx.query('SELECT * FROM check_imei_availability($1)', [l.imei])).rows[0];
  if (avail?.is_enrolled && avail.owning_tenant_id !== tenantId) throw conflict('IMEI is already enrolled under another tenant', 'IMEI_ALREADY_ENROLLED');
  let device = (await tx.query('SELECT * FROM devices WHERE imei = $1 OR imei_2 = $1', [l.imei])).rows[0];
  if (device) {
    const active = await tx.query(`SELECT 1 FROM loans WHERE device_id = $1 AND status IN ('active','overdue')`, [device.id]);
    if (active.rowCount) throw conflict('Device already has an active loan', 'DEVICE_HAS_ACTIVE_LOAN');
    device = (await tx.query('UPDATE devices SET borrower_id = $2, updated_at = NOW() WHERE id = $1 RETURNING *', [device.id, l.borrower_id])).rows[0];
  } else {
    device = (
      await tx.query(`INSERT INTO devices (tenant_id, borrower_id, imei, model) VALUES ($1, $2, $3, $4) RETURNING *`, [
        tenantId, l.borrower_id, l.imei, l.model ?? 'UNKNOWN',
      ])
    ).rows[0];
  }
  const accountRef = l.account_ref ?? `LND-${Math.floor(100000 + Math.random() * 900000)}`;
  const loan = (
    await tx.query(
      `INSERT INTO loans (tenant_id, account_ref, borrower_id, device_id, principal_amount, outstanding_balance, installment_amount,
                          installment_period_days, amount_due, next_due_at, currency)
       VALUES ($1,$2,$3,$4,$5::numeric,$5::numeric,$6::numeric,$7,LEAST($6::numeric,$5::numeric),$8,$9) RETURNING *`,
      [tenantId, accountRef, l.borrower_id, device.id, l.principal_amount, l.installment_amount, l.installment_period_days, l.first_due_at, l.currency],
    )
  ).rows[0];
  return { loan, device };
}

adminRouter.post(
  '/api/v1/admin/loans',
  requireAdmin(...ALL_ROLES),
  ah(async (req, res) => {
    const l = loanSchema.parse(req.body);
    const out = await withTenant(tenantOf(req), async (tx) => {
      try {
        return await createLoan(tx, tenantOf(req), l);
      } catch (err: any) {
        if (err.code === '23503') throw badRequest('Unknown borrower_id');
        if (err.code === '23505') throw conflict('Duplicate account_ref or IMEI');
        throw err;
      }
    });
    res.status(201).json({ loan: out.loan, device: { id: out.device.id, imei: out.device.imei, state: out.device.state } });
  }),
);

adminRouter.get(
  '/api/v1/admin/loans',
  requireAdmin(...ALL_ROLES),
  ah(async (req, res) => {
    const { status, limit, offset } = z
      .object({ status: z.enum(['draft', 'active', 'overdue', 'paid_off', 'defaulted']).optional(), limit: z.coerce.number().int().min(1).max(500).default(50), offset: z.coerce.number().int().min(0).default(0) })
      .parse(req.query);
    const rows = await withTenant(tenantOf(req), async (tx) =>
      (await tx.query(`SELECT * FROM loans WHERE ($1::text IS NULL OR status = $1) ORDER BY issued_at DESC LIMIT $2 OFFSET $3`, [status ?? null, limit, offset])).rows,
    );
    res.json({ data: rows, limit, offset });
  }),
);

/** Manual repayment entry from the portal (cash desk). Goes through the same ledger worker. */
adminRouter.post(
  '/api/v1/admin/loans/:loan_id/repayments',
  requireAdmin('admin', 'credit_manager', 'operator'),
  ah(async (req, res) => {
    const body = z.object({ amount: z.number().positive(), payment_method: z.string().default('CASH'), reference: z.string().max(128).optional() }).parse(req.body);
    const loanId = param(req, 'loan_id');
    if (!isUuid(loanId)) throw notFound('Loan not found');
    const reference = body.reference ?? `manual-${crypto.randomUUID()}`;
    const job: RepaymentJob = { tenantId: tenantOf(req), idempotencyKey: reference, transactionId: reference, loanId, amount: body.amount, paymentMethod: body.payment_method };
    await queue(QUEUES.repaymentWebhooks).add('PROCESS_REPAYMENT', job, { jobId: jobId('repay', tenantOf(req), reference) });
    res.status(202).json({ status: 'ACCEPTED', reference });
  }),
);

// ---------------------------------------------------------------- outbound webhook subscriptions

adminRouter.get(
  '/api/v1/admin/webhooks',
  requireAdmin('admin'),
  ah(async (req, res) => {
    const rows = await withTenant(tenantOf(req), async (tx) => (await tx.query('SELECT id, url, events, is_active, created_at FROM webhook_subscriptions ORDER BY created_at')).rows);
    res.json({ data: rows });
  }),
);

adminRouter.post(
  '/api/v1/admin/webhooks',
  requireAdmin('admin'),
  ah(async (req, res) => {
    const body = z.object({ url: z.string().url(), events: z.array(z.enum([...WEBHOOK_EVENTS, '*'])).min(1) }).parse(req.body);
    const row = await withTenant(tenantOf(req), async (tx) =>
      (await tx.query('INSERT INTO webhook_subscriptions (tenant_id, url, events) VALUES ($1, $2, $3) RETURNING *', [tenantOf(req), body.url, body.events])).rows[0],
    );
    res.status(201).json(row); // secret is only returned once, at creation
  }),
);

adminRouter.delete(
  '/api/v1/admin/webhooks/:webhook_id',
  requireAdmin('admin'),
  ah(async (req, res) => {
    const id = param(req, 'webhook_id');
    if (!isUuid(id)) throw notFound('Webhook not found');
    const r = await withTenant(tenantOf(req), (tx) => tx.query('DELETE FROM webhook_subscriptions WHERE id = $1', [id]));
    if (!r.rowCount) throw notFound('Webhook not found');
    res.status(204).end();
  }),
);

// ---------------------------------------------------------------- bulk import / export

adminRouter.get(
  '/api/v1/admin/export/:dataset',
  requireAdmin('admin', 'credit_manager'),
  ah(async (req, res) => {
    const { format } = z.object({ format: z.enum(['csv', 'json']).default('csv') }).parse(req.query);
    const dataset = param(req, 'dataset');
    const queries: Record<string, string> = {
      devices: `SELECT id, imei, imei_2, serial_number, manufacturer, model, state, lock_status, is_tampered, battery_level, last_heartbeat_at, enrolled_at, borrower_id FROM devices ORDER BY created_at`,
      loans: `SELECT l.id, l.account_ref, l.borrower_id, l.device_id, d.imei, l.principal_amount, l.outstanding_balance, l.amount_due, l.next_due_at, l.currency, l.status, l.issued_at FROM loans l JOIN devices d ON d.tenant_id = l.tenant_id AND d.id = l.device_id ORDER BY l.issued_at`,
      audit: `SELECT id, device_id, event_type, status, target_state, reason, payload, created_at, acknowledged_at FROM device_lock_events ORDER BY created_at DESC LIMIT 50000`,
    };
    const sql = queries[dataset];
    if (!sql) throw notFound('Unknown dataset (devices | loans | audit)');
    const rows = await withTenant(tenantOf(req), async (tx) => (await tx.query(sql)).rows);
    if (format === 'json') {
      res.json({ data: rows });
      return;
    }
    const csv = toCsv(rows.map((r) => Object.fromEntries(Object.entries(r).map(([k, v]) => [k, v instanceof Date ? v.toISOString() : typeof v === 'object' && v !== null ? JSON.stringify(v) : v]))), { header: true });
    res.setHeader('content-type', 'text/csv');
    res.setHeader('content-disposition', `attachment; filename="${dataset}.csv"`);
    res.send(csv);
  }),
);

const importRow = z.object({
  national_id: z.string().min(1),
  first_name: z.string().min(1),
  last_name: z.string().min(1),
  phone_number: z.string().min(5),
  email: z.string().email().optional().or(z.literal('')),
  imei: z.string().regex(/^\d{14,17}$/),
  model: z.string().optional(),
  principal_amount: z.coerce.number().positive(),
  installment_amount: z.coerce.number().positive(),
  installment_period_days: z.coerce.number().int().positive().default(30),
  first_due_at: z.coerce.date(),
  currency: z.string().length(3).default('USD'),
  account_ref: z.string().optional(),
});

/**
 * Bulk IMEI + loan contract mapping import. Body: CSV (text/csv) or JSON {rows: [...]}.
 * Each row is processed in its own savepoint; failures are reported per row.
 */
adminRouter.post(
  '/api/v1/admin/import/loans',
  requireAdmin('admin', 'credit_manager'),
  ah(async (req, res) => {
    const records: Record<string, unknown>[] =
      typeof req.body === 'string' ? parseCsv(req.body, { columns: true, skip_empty_lines: true, trim: true }) : (req.body?.rows ?? []);
    if (!Array.isArray(records) || records.length === 0) throw badRequest('No rows supplied');
    if (records.length > 5000) throw badRequest('Maximum 5000 rows per import');
    const tenantId = tenantOf(req);
    const results = await withTenant(tenantId, async (tx) => {
      const out: { row: number; status: 'created' | 'error'; account_ref?: string; device_id?: string; error?: string }[] = [];
      for (let i = 0; i < records.length; i++) {
        await tx.query('SAVEPOINT import_row');
        try {
          const r = importRow.parse(records[i]);
          const borrower = (
            await tx.query(
              `INSERT INTO borrowers (tenant_id, national_id, first_name, last_name, email, phone_number) VALUES ($1,$2,$3,$4,$5,$6)
               ON CONFLICT (tenant_id, national_id) DO UPDATE SET phone_number = EXCLUDED.phone_number RETURNING id`,
              [tenantId, r.national_id, r.first_name, r.last_name, r.email || null, r.phone_number],
            )
          ).rows[0];
          const { loan, device } = await createLoan(tx, tenantId, { ...r, borrower_id: borrower.id });
          await tx.query('RELEASE SAVEPOINT import_row');
          out.push({ row: i + 1, status: 'created', account_ref: loan.account_ref, device_id: device.id });
        } catch (err: any) {
          await tx.query('ROLLBACK TO SAVEPOINT import_row');
          out.push({ row: i + 1, status: 'error', error: err?.issues ? JSON.stringify(err.issues.map((x: any) => `${x.path.join('.')}: ${x.message}`)) : err.message });
        }
      }
      return out;
    });
    res.status(207).json({
      created: results.filter((r) => r.status === 'created').length,
      failed: results.filter((r) => r.status === 'error').length,
      results,
    });
  }),
);
