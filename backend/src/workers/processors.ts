import crypto from 'node:crypto';
import { withTenant } from '../db/pool.js';
import { logger } from '../lib/logger.js';
import { keys, redis } from '../lib/redis.js';
import {
  EVENT_TYPE_FOR_ACTION,
  canTransition,
  commandForState,
  evaluateDelinquency,
  isEscalation,
  isLockedState,
  nextState,
  type DeviceState,
} from '../domain/stateMachine.js';
import { enforcementHours, isTenantSuspended, listActiveTenants, loadConfigTx } from '../services/tenantService.js';
import { getDeviceForUpdate, writeSnapshot } from '../services/deviceService.js';
import { FcmTokenExpiredError, publishMqtt, sendFcm, type DeviceCommandMessage } from '../services/messaging.js';
import { emitWebhookEvent } from '../services/outboundWebhooks.js';
import {
  QUEUES,
  enqueueDeviceCommand,
  jobId as buildJobId,
  queue,
  type DeviceCommandJob,
  type FcmJob,
  type OverdueJob,
  type RepaymentJob,
} from '../services/queues.js';

export class RetryableError extends Error {}

// -------------------------------------------------------------------------
// device-commands — per-device Redis mutex + transactional state transition
// -------------------------------------------------------------------------

export interface DeviceCommandResult {
  status: 'APPLIED' | 'SKIPPED_SUSPENDED' | 'SKIPPED_INVALID_TRANSITION' | 'SKIPPED_NOT_FOUND';
  commandId?: string;
  from?: DeviceState;
  to?: DeviceState;
  delivered?: { fcm: boolean; mqtt: boolean };
}

export async function processDeviceCommand(job: DeviceCommandJob, jobId: string): Promise<DeviceCommandResult> {
  const { tenantId, deviceId, action, reason } = job;

  // Lender suspension kill-switch: exit safely without altering state.
  if (await isTenantSuspended(tenantId)) return { status: 'SKIPPED_SUSPENDED' };

  const mutexKey = keys.deviceMutex(deviceId);
  const acquired = await redis().set(mutexKey, jobId, 'EX', 10, 'NX');
  if (!acquired) throw new RetryableError(`Device lock contention for device ${deviceId}. Retrying...`);

  try {
    const outcome = await withTenant(tenantId, async (tx) => {
      const device = await getDeviceForUpdate(tx, deviceId);
      if (!device) return { status: 'SKIPPED_NOT_FOUND' as const };
      if (!canTransition(device.state, action)) {
        logger.warn({ deviceId, from: device.state, action }, 'ignoring invalid transition');
        return { status: 'SKIPPED_INVALID_TRANSITION' as const, from: device.state };
      }
      const to = nextState(device.state, action);
      const wasLocked = isLockedState(device.state);
      const willLock = isLockedState(to);
      let lockStatus = device.lock_status;
      if (willLock && (!wasLocked || device.lock_status !== 'locked')) lockStatus = 'pending_lock';
      if (!willLock && (wasLocked || device.lock_status === 'pending_lock')) lockStatus = 'pending_unlock';

      await tx.query(
        `UPDATE devices SET state = $1, lock_status = $2, last_command_at = NOW(), updated_at = NOW() WHERE id = $3`,
        [to, lockStatus, deviceId],
      );
      const commandId = crypto.randomUUID();
      await tx.query(
        `INSERT INTO device_lock_events (tenant_id, id, device_id, event_type, status, target_state, reason, payload)
         VALUES ($1, $2, $3, $4, 'queued', $5, $6, $7)`,
        [tenantId, commandId, deviceId, EVENT_TYPE_FOR_ACTION[action], to, reason, JSON.stringify({ action, source: job.source, loan_id: job.loanId ?? null })],
      );
      if (to !== device.state) {
        await emitWebhookEvent(tenantId, 'device.state_changed', { device_id: deviceId, imei: device.imei, from: device.state, to, reason }, tx);
      }
      if (to === 'RELEASED') {
        await emitWebhookEvent(tenantId, 'device.released', { device_id: deviceId, imei: device.imei, reason }, tx);
      }
      return { status: 'APPLIED' as const, commandId, from: device.state, to, fcmToken: device.fcm_token, lockStatus };
    });

    if (outcome.status !== 'APPLIED') return outcome;

    await writeSnapshot(tenantId, deviceId, outcome.to, outcome.lockStatus);

    // Dual-channel dispatch (FCM high-priority + MQTT retained fallback)
    const msg: DeviceCommandMessage = {
      commandType: action,
      commandId: outcome.commandId,
      targetState: outcome.to,
      timestamp: String(Date.now()),
    };
    let fcm = false;
    if (outcome.fcmToken) {
      const fcmJob: FcmJob = { tenantId, deviceId, fcmToken: outcome.fcmToken, action, commandId: outcome.commandId, targetState: outcome.to };
      await queue(QUEUES.fcmDispatcher).add('SEND_FCM_PUSH', fcmJob, { jobId: buildJobId('fcm', outcome.commandId) });
      fcm = true;
    }
    const mqttSent = await publishMqtt(tenantId, deviceId, msg).catch(() => false);
    if (fcm || mqttSent) {
      await withTenant(tenantId, (tx) =>
        tx.query(`UPDATE device_lock_events SET status = 'sent' WHERE id = $1 AND status = 'queued'`, [outcome.commandId]),
      );
    }
    return { status: 'APPLIED', commandId: outcome.commandId, from: outcome.from, to: outcome.to, delivered: { fcm, mqtt: mqttSent } };
  } finally {
    // Release the distributed mutex only if we still own it.
    await redis().eval(
      `if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end`,
      1,
      mutexKey,
      jobId,
    );
  }
}

// -------------------------------------------------------------------------
// fcm-dispatcher
// -------------------------------------------------------------------------

export async function processFcm(job: FcmJob): Promise<{ status: string; messageId?: string }> {
  try {
    const messageId = await sendFcm(job.fcmToken, {
      commandType: job.action,
      commandId: job.commandId,
      targetState: job.targetState,
      timestamp: String(Date.now()),
    });
    return { status: 'SENT', messageId };
  } catch (err) {
    if (err instanceof FcmTokenExpiredError) {
      logger.warn({ deviceId: job.deviceId }, 'Unregistered FCM token encountered');
      await withTenant(job.tenantId, (tx) =>
        tx.query('UPDATE devices SET fcm_token = NULL WHERE id = $1 AND fcm_token = $2', [job.deviceId, job.fcmToken]),
      );
      return { status: 'TOKEN_EXPIRED' };
    }
    throw err;
  }
}

// -------------------------------------------------------------------------
// repayment-webhooks — transactional ledger update + downstream unlock
// -------------------------------------------------------------------------

export interface RepaymentResult {
  status: 'APPLIED' | 'DUPLICATE' | 'LOAN_NOT_FOUND' | 'SKIPPED_SUSPENDED';
  remainingBalance?: number;
  amountDue?: number;
  loanStatus?: string;
  deviceCommand?: string;
}

export async function processRepayment(job: RepaymentJob, now = new Date()): Promise<RepaymentResult> {
  if (await isTenantSuspended(job.tenantId)) return { status: 'SKIPPED_SUSPENDED' };
  const result = await withTenant(job.tenantId, async (tx) => {
    const cfg = await loadConfigTx(tx, job.tenantId);
    const loanRes = await tx.query(
      `SELECT l.*, d.state AS device_state FROM loans l JOIN devices d ON d.tenant_id = l.tenant_id AND d.id = l.device_id
        WHERE ${job.loanId ? 'l.id = $1' : 'l.account_ref = $1'} FOR UPDATE OF l`,
      [job.loanId ?? job.accountRef],
    );
    const loan = loanRes.rows[0];
    if (!loan) return { status: 'LOAN_NOT_FOUND' as const };

    const inserted = await tx.query(
      `INSERT INTO repayments (tenant_id, loan_id, amount, payment_method, external_reference)
       VALUES ($1, $2, $3, $4, $5) ON CONFLICT (tenant_id, external_reference) DO NOTHING RETURNING id`,
      [job.tenantId, loan.id, job.amount, job.paymentMethod, job.transactionId],
    );
    if (inserted.rowCount === 0) return { status: 'DUPLICATE' as const };

    const round = (n: number) => Math.round(n * 100) / 100;
    let outstanding = round(Math.max(0, loan.outstanding_balance - job.amount));
    let amountDue = round(loan.amount_due - job.amount);
    let nextDueAt = new Date(loan.next_due_at);
    let status: string = loan.status;

    if (outstanding === 0) {
      amountDue = 0;
      status = 'paid_off';
    } else {
      // Roll forward installments covered by this payment (credit carries over).
      while (amountDue <= 0) {
        nextDueAt = new Date(nextDueAt.getTime() + loan.installment_period_days * 86_400_000);
        amountDue = round(amountDue + Number(loan.installment_amount));
      }
      amountDue = Math.min(amountDue, outstanding);
      status = nextDueAt.getTime() < now.getTime() ? 'overdue' : 'active';
    }

    await tx.query(
      `UPDATE loans SET outstanding_balance = $1, amount_due = $2, next_due_at = $3, status = $4, updated_at = NOW() WHERE id = $5`,
      [outstanding, amountDue, nextDueAt, status, loan.id],
    );

    await emitWebhookEvent(
      job.tenantId,
      'repayment.received',
      { loan_id: loan.id, account_ref: loan.account_ref, amount: job.amount, remaining_balance: outstanding, transaction_id: job.transactionId },
      tx,
    );

    const deviceState = loan.device_state as DeviceState;
    let command: DeviceCommandJob['action'] | undefined;
    if (status === 'paid_off') {
      command = 'RELEASE';
    } else {
      const desired = evaluateDelinquency({ now, nextDueAt, amountDue, ...enforcementHours(cfg) });
      if (isEscalation(desired, deviceState) && deviceState !== 'PENDING_ENROLLMENT') command = 'UNLOCK';
    }
    return { status: 'APPLIED' as const, loan, outstanding, amountDue, loanStatus: status, command };
  });

  if (result.status !== 'APPLIED') return { status: result.status };
  if (result.command) {
    await enqueueDeviceCommand(
      {
        tenantId: job.tenantId,
        deviceId: result.loan.device_id,
        loanId: result.loan.id,
        action: result.command,
        reason: result.command === 'RELEASE' ? 'LOAN_PAID_OFF' : 'REPAYMENT_RECEIVED',
        source: 'repayment',
      },
      job.transactionId,
    );
  }
  return {
    status: 'APPLIED',
    remainingBalance: result.outstanding,
    amountDue: result.amountDue,
    loanStatus: result.loanStatus,
    deviceCommand: result.command,
  };
}

// -------------------------------------------------------------------------
// overdue-evaluator — progressive lockdown escalation
// -------------------------------------------------------------------------

export async function processOverdue(job: OverdueJob, now = new Date()): Promise<{ processed: number; escalated: number }> {
  if (await isTenantSuspended(job.tenantId)) return { processed: 0, escalated: 0 };
  const commands = await withTenant(job.tenantId, async (tx) => {
    const cfg = await loadConfigTx(tx, job.tenantId);
    const hours = enforcementHours(cfg);
    const { rows } = await tx.query(
      `SELECT l.id, l.device_id, l.next_due_at, l.amount_due, l.status, d.state AS device_state
         FROM loans l JOIN devices d ON d.tenant_id = l.tenant_id AND d.id = l.device_id
        WHERE l.status IN ('active', 'overdue')
          AND l.amount_due > 0
          AND d.state IN ('ACTIVE', 'GRACE', 'SOFT_LOCKED')
          AND l.next_due_at - make_interval(hours => $1) < $2
        FOR UPDATE OF l SKIP LOCKED`,
      [hours.warningHoursBeforeLock, now],
    );
    const out: DeviceCommandJob[] = [];
    const dedupe: string[] = [];
    for (const loan of rows) {
      const nextDueAt = new Date(loan.next_due_at);
      if (loan.status === 'active' && nextDueAt < now) {
        await tx.query(`UPDATE loans SET status = 'overdue', updated_at = NOW() WHERE id = $1 AND status = 'active'`, [loan.id]);
      }
      const desired = evaluateDelinquency({ now, nextDueAt, amountDue: loan.amount_due, ...hours });
      if (isEscalation(loan.device_state, desired)) {
        out.push({
          tenantId: job.tenantId,
          deviceId: loan.device_id,
          loanId: loan.id,
          action: commandForState(desired),
          reason: desired === 'GRACE' ? 'PAYMENT_DUE' : 'PAYMENT_OVERDUE',
          source: 'overdue_evaluator',
        });
        dedupe.push(`${loan.id}:${nextDueAt.getTime()}:${desired}`);
      }
    }
    return { processed: rows.length, out, dedupe };
  });
  for (let i = 0; i < commands.out.length; i++) await enqueueDeviceCommand(commands.out[i]!, commands.dedupe[i]);
  return { processed: commands.processed, escalated: commands.out.length };
}

/** Fan-out: schedules one overdue-evaluator job per active tenant. */
export async function scheduleOverdueForAllTenants(): Promise<number> {
  const tenants = await listActiveTenants();
  const slot = Math.floor(Date.now() / 60_000);
  for (const t of tenants) {
    await queue(QUEUES.overdueEvaluator).add('EVALUATE_TENANT', { tenantId: t.id }, { jobId: buildJobId('overdue', t.id, slot) });
  }
  return tenants.length;
}

// -------------------------------------------------------------------------
// heartbeat-reconciliation — heartbeat loss, state drift, offline policy
// -------------------------------------------------------------------------

export const HEARTBEAT_LOST_AFTER_MS = 60 * 60 * 1000;

export async function processHeartbeatReconciliation(
  tenantId: string,
  now = new Date(),
): Promise<{ lost: number; requeued: number; offlineLocked: number }> {
  if (await isTenantSuspended(tenantId)) return { lost: 0, requeued: 0, offlineLocked: 0 };
  const result = await withTenant(tenantId, async (tx) => {
    const cfg = await loadConfigTx(tx, tenantId);
    const lostBefore = new Date(now.getTime() - HEARTBEAT_LOST_AFTER_MS);

    const lost = await tx.query(
      `UPDATE devices SET heartbeat_lost_notified = true
        WHERE state NOT IN ('PENDING_ENROLLMENT', 'RELEASED') AND last_heartbeat_at < $1 AND heartbeat_lost_notified = false
        RETURNING id, imei, last_heartbeat_at, state`,
      [lostBefore],
    );
    for (const d of lost.rows) {
      await emitWebhookEvent(tenantId, 'device.heartbeat_lost', { device_id: d.id, imei: d.imei, state: d.state, last_heartbeat_at: d.last_heartbeat_at }, tx);
    }

    // Postgres says X but the handset keeps reporting Y for > 2 check-ins: re-issue.
    const drift = await tx.query(
      `UPDATE devices SET state_mismatch_count = 0
        WHERE state_mismatch_count > 2 AND state NOT IN ('PENDING_ENROLLMENT')
        RETURNING id, state`,
    );
    const commands: DeviceCommandJob[] = drift.rows.map((d) => ({
      tenantId,
      deviceId: d.id,
      action: commandForState(d.state),
      reason: 'STATE_RECONCILIATION',
      source: 'reconciliation' as const,
    }));

    // Mirror the on-device offline lockout policy server-side.
    const policy = cfg.enforcement_policies.offline_lock_fallback;
    let offlineLocked = 0;
    if (policy.action_if_exceeded === 'HARD_LOCK' && policy.max_offline_days > 0) {
      const offlineBefore = new Date(now.getTime() - policy.max_offline_days * 86_400_000);
      const offline = await tx.query(
        `SELECT id FROM devices WHERE state IN ('ACTIVE', 'GRACE', 'SOFT_LOCKED') AND last_heartbeat_at < $1`,
        [offlineBefore],
      );
      for (const d of offline.rows) {
        commands.push({ tenantId, deviceId: d.id, action: 'LOCK', reason: 'OFFLINE_THRESHOLD_EXCEEDED', source: 'reconciliation' });
        offlineLocked++;
      }
    }
    return { lost: lost.rowCount ?? 0, commands, offlineLocked };
  });
  const slot = Math.floor(now.getTime() / (15 * 60_000));
  for (const c of result.commands) await enqueueDeviceCommand(c, `recon:${slot}:${c.reason}`);
  return { lost: result.lost, requeued: result.commands.length - result.offlineLocked, offlineLocked: result.offlineLocked };
}

export async function scheduleReconciliationForAllTenants(): Promise<number> {
  const tenants = await listActiveTenants();
  const slot = Math.floor(Date.now() / 60_000);
  for (const t of tenants) {
    await queue(QUEUES.heartbeatReconciliation).add('RECONCILE_TENANT', { tenantId: t.id }, { jobId: buildJobId('recon', t.id, slot) });
  }
  return tenants.length;
}
