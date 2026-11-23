/**
 * End-to-end flow against a real PostgreSQL (RLS enforced) + Redis.
 * Skipped automatically when the databases are not reachable.
 *
 *   docker compose up -d postgres redis   # or local services
 *   npm run migrate && npm test
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import request from 'supertest';
import pg from 'pg';
import { createApp } from '../src/app.js';
import { config } from '../src/config.js';
import { closeDb, withTenant } from '../src/db/pool.js';
import { closeRedis, redis } from '../src/lib/redis.js';
import { hmacSha256Hex } from '../src/lib/crypto.js';
import { QUEUES, closeQueues, queue } from '../src/services/queues.js';
import { setFcmSender } from '../src/services/messaging.js';
import { generateOfflineUnlockCode, offlineWindow } from '../src/domain/offlineUnlock.js';
import {
  processDeviceCommand,
  processHeartbeatReconciliation,
  processOverdue,
  processRepayment,
} from '../src/workers/processors.js';
import { migrate } from '../src/db/migrate.js';

async function reachable(): Promise<boolean> {
  const c = new pg.Client({ connectionString: config.databaseAdminUrl, connectionTimeoutMillis: 1500 });
  try {
    await c.connect();
    await c.end();
    return true;
  } catch {
    return false;
  }
}

const enabled = await reachable();
const app = createApp();
const PLATFORM = { 'x-platform-key': config.platformAdminKey };
const uniq = Date.now().toString(36);
const randomImei = () => String(860000000000000 + Math.floor(Math.random() * 9_999_999_999)).slice(0, 15);

async function drainDeviceCommands() {
  const q = queue(QUEUES.deviceCommands);
  const jobs = await q.getJobs(['waiting', 'prioritized', 'delayed']);
  const results = [];
  for (const job of jobs) {
    results.push(await processDeviceCommand(job.data, job.id!));
    await job.remove();
  }
  return results;
}

async function drainRepayments() {
  const q = queue(QUEUES.repaymentWebhooks);
  const jobs = await q.getJobs(['waiting', 'prioritized', 'delayed']);
  const results = [];
  for (const job of jobs) {
    results.push(await processRepayment(job.data));
    await job.remove();
  }
  return results;
}

async function createTenant(slug: string) {
  const res = await request(app)
    .post('/api/v1/platform/tenants')
    .set(PLATFORM)
    .send({ name: `Tenant ${slug}`, slug, admin_email: `admin@${slug}.test`, admin_password: 'correct-horse-battery' });
  expect(res.status).toBe(201);
  const login = await request(app)
    .post('/api/v1/admin/auth/login')
    .send({ tenant_slug: slug, email: `admin@${slug}.test`, password: 'correct-horse-battery' });
  expect(login.status).toBe(200);
  return { tenant: res.body, token: login.body.access_token as string };
}

describe.skipIf(!enabled)('device financing lifecycle (integration)', () => {
  let A: Awaited<ReturnType<typeof createTenant>>;
  let B: Awaited<ReturnType<typeof createTenant>>;
  let deviceId: string;
  let deviceJwt: string;
  let loanId: string;
  let accountRef: string;
  let offlineSecret: string;
  const imei = randomImei();
  const fcmSent: Record<string, string>[] = [];

  beforeAll(async () => {
    await migrate(config.databaseAdminUrl);
    setFcmSender(async (_t, data) => {
      fcmSent.push(data);
      return 'msg-1';
    });
    for (const name of Object.values(QUEUES)) await queue(name).obliterate({ force: true });
    A = await createTenant(`alpha-${uniq}`);
    B = await createTenant(`beta-${uniq}`);
  });

  afterAll(async () => {
    setFcmSender(undefined);
    await closeQueues();
    await closeRedis();
    await closeDb();
  });

  it('registers a borrower and loan at the POS (device PENDING_ENROLLMENT)', async () => {
    const auth = { authorization: `Bearer ${A.token}` };
    const b = await request(app)
      .post('/api/v1/admin/borrowers')
      .set(auth)
      .send({ national_id: `NIN-${uniq}`, first_name: 'Ada', last_name: 'Obi', phone_number: '+2348000000000' });
    expect(b.status).toBe(201);
    const due = new Date(Date.now() + 10 * 86_400_000).toISOString();
    const l = await request(app)
      .post('/api/v1/admin/loans')
      .set(auth)
      .send({ borrower_id: b.body.id, imei, principal_amount: 270, installment_amount: 45, first_due_at: due, model: 'Galaxy A14' });
    expect(l.status).toBe(201);
    expect(l.body.device.state).toBe('PENDING_ENROLLMENT');
    loanId = l.body.loan.id;
    accountRef = l.body.loan.account_ref;
  });

  it('enrolls the device with a provisioning token and returns device credentials', async () => {
    const prov = await request(app)
      .post(`/api/v1/admin/tenants/${A.tenant.id}/provisioning-tokens`)
      .set({ authorization: `Bearer ${A.token}` })
      .send({});
    expect(prov.status).toBe(201);
    expect(prov.body.qr_payload['android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE'].tenant_id).toBe(A.tenant.id);

    const enroll = await request(app)
      .post('/api/v1/devices/enroll')
      .send({
        provisioning_token: prov.body.provisioning_token,
        tenant_id: A.tenant.id,
        hardware_identifiers: { imei_1: imei, serial_number: 'RF8M30ABCDE', mac_address: '00:0a:95:9d:68:16' },
        device_metadata: { manufacturer: 'Samsung', model: 'Galaxy A14', android_version: '13', api_level: 33 },
        fcm_token: 'fcm-token-abc',
      });
    expect(enroll.status).toBe(201);
    expect(enroll.body.status).toBe('ENROLLED');
    expect(enroll.body.current_state).toBe('ACTIVE');
    expect(enroll.body.endpoints.heartbeat_url).toContain(enroll.body.device_id);
    deviceId = enroll.body.device_id;
    deviceJwt = enroll.body.device_jwt;
    offlineSecret = enroll.body.offline_unlock_secret;

    // The other tenant cannot claim the same IMEI
    const provB = await request(app).post(`/api/v1/admin/tenants/${B.tenant.id}/provisioning-tokens`).set({ authorization: `Bearer ${B.token}` }).send({});
    const dup = await request(app)
      .post('/api/v1/devices/enroll')
      .send({
        provisioning_token: provB.body.provisioning_token,
        tenant_id: B.tenant.id,
        hardware_identifiers: { imei_1: imei },
        device_metadata: { manufacturer: 'Samsung', model: 'Galaxy A14', android_version: '13', api_level: 33 },
      });
    expect(dup.status).toBe(409);
    expect(dup.body.error.code).toBe('IMEI_ALREADY_ENROLLED');
  });

  it('serves the dynamic tenant config to the device (with ETag)', async () => {
    const res = await request(app).get(`/api/v1/tenants/${A.tenant.id}/config`).set({ authorization: `Bearer ${deviceJwt}` });
    expect(res.status).toBe(200);
    expect(res.body.identity.theme.primary_color).toMatch(/^#/);
    const cached = await request(app)
      .get(`/api/v1/tenants/${A.tenant.id}/config`)
      .set({ authorization: `Bearer ${deviceJwt}`, 'if-none-match': res.headers.etag });
    expect(cached.status).toBe(304);
    const cross = await request(app).get(`/api/v1/tenants/${B.tenant.id}/config`).set({ authorization: `Bearer ${deviceJwt}` });
    expect(cross.status).toBe(403);
  });

  it('enforces Row-Level Security between tenants', async () => {
    const res = await request(app).get(`/api/v1/admin/devices/${deviceId}`).set({ authorization: `Bearer ${B.token}` });
    expect(res.status).toBe(404);
    const rows = await withTenant(B.tenant.id, async (tx) => (await tx.query('SELECT id FROM devices')).rows);
    expect(rows).toHaveLength(0);
    // Without a tenant context nothing is visible at all.
    const client = new pg.Client({ connectionString: config.databaseUrl });
    await client.connect();
    expect((await client.query('SELECT count(*)::int AS n FROM devices')).rows[0].n).toBe(0);
    await expect(client.query(`INSERT INTO borrowers (tenant_id, national_id, first_name, last_name, phone_number) VALUES ($1, 'x', 'x', 'x', 'x')`, [A.tenant.id])).rejects.toThrow(/row-level security/);
    await client.end();
  });

  it('locks via the lender API, delivers FCM and finalises on acknowledgement', async () => {
    const lock = await request(app).post(`/api/v1/devices/${deviceId}/lock`).set({ authorization: `Bearer ${A.token}` }).send({ reason: 'MANUAL_TEST' });
    expect(lock.status).toBe(202);
    const [result] = await drainDeviceCommands();
    expect(result!.status).toBe('APPLIED');
    expect(result!.to).toBe('HARD_LOCKED');

    const fcmJobs = await queue(QUEUES.fcmDispatcher).getJobs(['waiting', 'prioritized']);
    expect(fcmJobs[0]!.data.action).toBe('LOCK');

    const hb = await request(app)
      .post(`/api/v1/devices/${deviceId}/heartbeat`)
      .set({ authorization: `Bearer ${deviceJwt}` })
      .send({ reported_state: 'ACTIVE', battery_level: 80 });
    expect(hb.status).toBe(200);
    expect(hb.body.desired_state).toBe('HARD_LOCKED');
    expect(hb.body.lock_status).toBe('pending_lock');
    expect(hb.body.pending_command.command_id).toBe(result!.commandId);
    expect(hb.body.loan.account_ref).toBe(accountRef);

    const ack = await request(app)
      .post(`/api/v1/devices/${deviceId}/commands/${result!.commandId}/ack`)
      .set({ authorization: `Bearer ${deviceJwt}` })
      .send({ applied_state: 'HARD_LOCKED' });
    expect(ack.status).toBe(200);
    const state = await request(app).get(`/api/v1/devices/${deviceId}/state`).set({ authorization: `Bearer ${A.token}` });
    expect(state.body).toMatchObject({ state: 'HARD_LOCKED', lock_status: 'locked' });
  });

  it('issues offline unlock codes that match the device algorithm', async () => {
    const res = await request(app).get(`/api/v1/admin/devices/${deviceId}/offline-unlock-code`).set({ authorization: `Bearer ${A.token}` });
    expect(res.status).toBe(200);
    const w = offlineWindow(Math.floor(Date.now() / 1000), config.offlineUnlockWindowSeconds);
    expect(res.body.code).toBe(generateOfflineUnlockCode(offlineSecret, deviceId, w));
  });

  it('verifies payment webhooks, is idempotent and unlocks on repayment', async () => {
    const body = JSON.stringify({ transactionId: `tx-${uniq}`, accountRef, amount: 45, idempotencyKey: `idem-${uniq}` });
    const bad = await request(app).post(`/api/v1/webhooks/payments/${A.tenant.id}`).set({ 'content-type': 'application/json', 'x-provider-signature': 'nope' }).send(body);
    expect(bad.status).toBe(401);

    const sig = hmacSha256Hex(A.tenant.inbound_webhook_secret, body);
    const ok = await request(app).post(`/api/v1/webhooks/payments/${A.tenant.id}`).set({ 'content-type': 'application/json', 'x-provider-signature': sig }).send(body);
    expect(ok.status).toBe(202);
    const dup = await request(app).post(`/api/v1/webhooks/payments/${A.tenant.id}`).set({ 'content-type': 'application/json', 'x-provider-signature': sig }).send(body);
    expect(dup.body.status).toBe('ACCEPTED_DUPLICATE');

    const [rep] = await drainRepayments();
    expect(rep).toMatchObject({ status: 'APPLIED', remainingBalance: 225, amountDue: 45, deviceCommand: 'UNLOCK' });
    const [cmd] = await drainDeviceCommands();
    expect(cmd!.to).toBe('ACTIVE');

    // Replaying the ledger job directly is also a no-op (DB-level idempotency)
    const replay = await processRepayment({ tenantId: A.tenant.id, idempotencyKey: 'x', transactionId: `tx-${uniq}`, accountRef, amount: 45, paymentMethod: 'X' });
    expect(replay.status).toBe('DUPLICATE');
  });

  it('progressively escalates overdue loans (grace -> soft -> hard)', async () => {
    const day = 86_400_000;
    const loan = await withTenant(A.tenant.id, async (tx) => (await tx.query('SELECT next_due_at FROM loans WHERE id = $1', [loanId])).rows[0]);
    const due = new Date(loan.next_due_at).getTime();

    let r = await processOverdue({ tenantId: A.tenant.id }, new Date(due - 2 * day));
    expect(r.escalated).toBe(0);

    r = await processOverdue({ tenantId: A.tenant.id }, new Date(due + 1 * day));
    expect(r.escalated).toBe(1);
    expect((await drainDeviceCommands())[0]!.to).toBe('GRACE');

    r = await processOverdue({ tenantId: A.tenant.id }, new Date(due + 4 * day));
    expect((await drainDeviceCommands())[0]!.to).toBe('SOFT_LOCKED');

    r = await processOverdue({ tenantId: A.tenant.id }, new Date(due + 6 * day));
    expect((await drainDeviceCommands())[0]!.to).toBe('HARD_LOCKED');

    // Already hard locked -> no further commands
    r = await processOverdue({ tenantId: A.tenant.id }, new Date(due + 7 * day));
    expect(r.escalated).toBe(0);
  });

  it('hard locks on tamper detection and emits webhooks to subscribers', async () => {
    const sub = await request(app).post('/api/v1/admin/webhooks').set({ authorization: `Bearer ${A.token}` }).send({ url: 'https://lender.example.com/hooks', events: ['*'] });
    expect(sub.status).toBe(201);
    await request(app).post(`/api/v1/devices/${deviceId}/unlock`).set({ authorization: `Bearer ${A.token}` }).send({});
    await drainDeviceCommands();

    const hb = await request(app)
      .post(`/api/v1/devices/${deviceId}/heartbeat`)
      .set({ authorization: `Bearer ${deviceJwt}` })
      .send({ reported_state: 'ACTIVE', integrity: { rooted: true, bootloader_unlocked: false, adb_enabled: false } });
    expect(hb.status).toBe(200);
    const [cmd] = await drainDeviceCommands();
    expect(cmd).toMatchObject({ to: 'HARD_LOCKED' });
    const deliveries = await queue(QUEUES.webhookDelivery).getJobs(['waiting', 'prioritized']);
    expect(deliveries.map((j) => j.data.event)).toContain('device.tamper_detected');
  });

  it('reconciles lost heartbeats and state drift', async () => {
    const r = await processHeartbeatReconciliation(A.tenant.id, new Date(Date.now() + 2 * 3_600_000));
    expect(r.lost).toBeGreaterThanOrEqual(1);
  });

  it('suspension kill-switch halts device transitions', async () => {
    await request(app).patch(`/api/v1/platform/tenants/${A.tenant.id}`).set(PLATFORM).send({ status: 'suspended' });
    const res = await processDeviceCommand({ tenantId: A.tenant.id, deviceId, action: 'UNLOCK', reason: 't', source: 'api' }, 'job-x');
    expect(res.status).toBe('SKIPPED_SUSPENDED');
    await request(app).patch(`/api/v1/platform/tenants/${A.tenant.id}`).set(PLATFORM).send({ status: 'active' });
    expect(await redis().get(`tenant:status:${A.tenant.id}`)).toBeNull();
  });

  it('reports fleet stats and exports CSV', async () => {
    const stats = await request(app).get('/api/v1/admin/fleet/stats').set({ authorization: `Bearer ${A.token}` });
    expect(stats.status).toBe(200);
    expect(stats.body.devices.hard_locked).toBe(1);
    expect(stats.body.devices.tampered).toBe(1);
    const csv = await request(app).get('/api/v1/admin/export/devices?format=csv').set({ authorization: `Bearer ${A.token}` });
    expect(csv.headers['content-type']).toContain('text/csv');
    expect(csv.text).toContain(imei);
  });

  it('bulk imports loans from CSV with per-row results', async () => {
    const csv = [
      'national_id,first_name,last_name,phone_number,imei,principal_amount,installment_amount,first_due_at',
      `NIN-${uniq}-2,Chinedu,Eze,+2348011111111,${randomImei()},300,50,2030-01-01`,
      `NIN-${uniq}-3,Bad,Row,+2348022222222,not-an-imei,300,50,2030-01-01`,
    ].join('\n');
    const res = await request(app).post('/api/v1/admin/import/loans').set({ authorization: `Bearer ${B.token}`, 'content-type': 'text/csv' }).send(csv);
    expect(res.status).toBe(207);
    expect(res.body.created).toBe(1);
    expect(res.body.failed).toBe(1);
  });

  it('updates branding and bumps config version', async () => {
    const res = await request(app)
      .put(`/api/v1/admin/tenants/${A.tenant.id}/branding`)
      .set({ authorization: `Bearer ${A.token}` })
      .send({ identity: { name: 'Alpha Credit', theme: { primary_color: '#E4002B' } } });
    expect(res.status).toBe(200);
    expect(res.body.version).toBe(2);
    expect(res.body.identity.theme.primary_color).toBe('#E4002B');
    const dev = await request(app).get(`/api/v1/tenants/${A.tenant.id}/config`).set({ authorization: `Bearer ${deviceJwt}` });
    expect(dev.body.identity.name).toBe('Alpha Credit');
  });

  it('releases the device when the loan is paid off', async () => {
    const res = await processRepayment({ tenantId: A.tenant.id, idempotencyKey: 'final', transactionId: `final-${uniq}`, loanId, amount: 1000, paymentMethod: 'BANK' });
    expect(res).toMatchObject({ status: 'APPLIED', loanStatus: 'paid_off', deviceCommand: 'RELEASE' });
    const [cmd] = await drainDeviceCommands();
    expect(cmd!.to).toBe('RELEASED');
    expect(fcmSent).toBeDefined();
  });
});
