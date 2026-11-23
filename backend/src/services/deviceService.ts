import type { Tx } from '../db/pool.js';
import { keys, redis } from '../lib/redis.js';
import { isLockedState, type DeviceState } from '../domain/stateMachine.js';
import { emitWebhookEvent } from './outboundWebhooks.js';

export interface DeviceRow {
  tenant_id: string;
  id: string;
  borrower_id: string | null;
  imei: string;
  imei_2: string | null;
  serial_number: string | null;
  model: string;
  manufacturer: string | null;
  state: DeviceState;
  lock_status: 'unlocked' | 'pending_lock' | 'locked' | 'pending_unlock';
  is_tampered: boolean;
  fcm_token: string | null;
  offline_secret: string;
  reported_state: string | null;
  state_mismatch_count: number;
  last_heartbeat_at: Date | null;
  battery_level: number | null;
  enrolled_at: Date | null;
}

export async function getDeviceForUpdate(tx: Tx, deviceId: string): Promise<DeviceRow | undefined> {
  const { rows } = await tx.query<DeviceRow>('SELECT * FROM devices WHERE id = $1 FOR UPDATE', [deviceId]);
  return rows[0];
}

export interface LoanSummary {
  loan_id: string;
  account_ref: string;
  currency: string;
  principal_amount: number;
  amount_due: number;
  outstanding_balance: number;
  next_due_at: string;
  days_overdue: number;
  status: string;
}

export async function getLoanSummary(tx: Tx, deviceId: string, now = new Date()): Promise<LoanSummary | null> {
  const { rows } = await tx.query(
    `SELECT id, account_ref, currency, principal_amount, amount_due, outstanding_balance, next_due_at, status
       FROM loans WHERE device_id = $1 AND status IN ('active', 'overdue', 'defaulted')
       ORDER BY issued_at DESC LIMIT 1`,
    [deviceId],
  );
  const l = rows[0];
  if (!l) return null;
  const due = new Date(l.next_due_at);
  const daysOverdue = l.amount_due > 0 ? Math.max(0, Math.floor((now.getTime() - due.getTime()) / 86_400_000)) : 0;
  return {
    loan_id: l.id,
    account_ref: l.account_ref,
    currency: l.currency,
    principal_amount: l.principal_amount,
    amount_due: l.amount_due,
    outstanding_balance: l.outstanding_balance,
    next_due_at: due.toISOString(),
    days_overdue: daysOverdue,
    status: l.status,
  };
}

/** Redis lock-state snapshot for instantaneous DPC check-in validation. */
export async function writeSnapshot(tenantId: string, deviceId: string, state: DeviceState, lockStatus: string): Promise<void> {
  await redis()
    .set(keys.deviceSnapshot(tenantId, deviceId), JSON.stringify({ state, lock_status: lockStatus, at: Date.now() }), 'EX', 86_400)
    .catch(() => undefined);
}

/**
 * Marks a command as acknowledged by the handset, finalising the physical
 * lock status and emitting device.locked / device.unlocked.
 */
export async function acknowledgeCommand(
  tx: Tx,
  tenantId: string,
  device: DeviceRow,
  commandId: string,
  appliedState: DeviceState,
): Promise<boolean> {
  const { rows } = await tx.query(
    `UPDATE device_lock_events SET status = 'acknowledged', acknowledged_at = NOW()
      WHERE id = $1 AND device_id = $2 AND status IN ('queued', 'sent')
      RETURNING target_state, reason`,
    [commandId, device.id],
  );
  if (!rows[0]) return false;
  // Only finalise if the acknowledged command is still the desired state.
  if (rows[0].target_state !== device.state || appliedState !== device.state) return true;
  const locked = isLockedState(device.state);
  const lockStatus = locked ? 'locked' : 'unlocked';
  await tx.query(`UPDATE devices SET lock_status = $1, reported_state = $2, state_mismatch_count = 0, updated_at = NOW() WHERE id = $3`, [
    lockStatus,
    appliedState,
    device.id,
  ]);
  await tx.query(
    `INSERT INTO device_lock_events (tenant_id, device_id, event_type, status, target_state, reason, payload)
     VALUES ($1, $2, 'HEARTBEAT_ACK', 'acknowledged', $3, $4, $5)`,
    [tenantId, device.id, appliedState, 'COMMAND_ACK', JSON.stringify({ command_id: commandId })],
  );
  const statusChanged = device.lock_status !== lockStatus;
  if (statusChanged) {
    await emitWebhookEvent(
      tenantId,
      locked ? 'device.locked' : 'device.unlocked',
      { device_id: device.id, imei: device.imei, state: device.state, command_id: commandId, reason: rows[0].reason },
      tx,
    );
  }
  await writeSnapshot(tenantId, device.id, device.state, lockStatus);
  return true;
}
