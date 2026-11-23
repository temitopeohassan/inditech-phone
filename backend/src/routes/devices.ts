import { Router } from 'express';
import { z } from 'zod';
import { config } from '../config.js';
import { withTenant, isUuid } from '../db/pool.js';
import { ah, param } from '../lib/http.js';
import { HttpError, conflict, forbidden, notFound } from '../lib/errors.js';
import { tokens } from '../lib/jwt.js';
import { requireDevice } from '../middleware/auth.js';
import { rateLimit } from '../middleware/rateLimit.js';
import { nextState, DEVICE_STATES, type DeviceState } from '../domain/stateMachine.js';
import { acknowledgeCommand, getDeviceForUpdate, getLoanSummary, writeSnapshot } from '../services/deviceService.js';
import { emitWebhookEvent } from '../services/outboundWebhooks.js';
import { enqueueDeviceCommand } from '../services/queues.js';
import { deviceTopic } from '../services/messaging.js';
import { isTenantSuspended, loadConfigTx } from '../services/tenantService.js';

export const devicesRouter = Router();

const imei = z.string().regex(/^\d{14,17}$/, 'IMEI must be 14-17 digits');

export const enrollmentSchema = z.object({
  provisioning_token: z.string().min(10),
  tenant_id: z.string().refine(isUuid, 'tenant_id must be a UUID'),
  hardware_identifiers: z.object({
    imei_1: imei,
    imei_2: imei.optional().nullable(),
    serial_number: z.string().max(128).optional().nullable(),
    mac_address: z.string().max(32).optional().nullable(),
  }),
  device_metadata: z.object({
    manufacturer: z.string().max(128),
    model: z.string().max(128),
    android_version: z.string().max(16),
    api_level: z.number().int(),
    build_fingerprint: z.string().max(255).optional(),
    knox_sdk_version: z.string().max(16).optional().nullable(),
    security_patch: z.string().max(16).optional(),
  }),
  fcm_token: z.string().optional().nullable(),
});

/** POST /api/v1/devices/enroll — Provisioning Token auth */
devicesRouter.post(
  '/api/v1/devices/enroll',
  rateLimit('enroll', 120, 60),
  ah(async (req, res) => {
    const body = enrollmentSchema.parse(req.body);
    const claims = tokens.verifyProvisioning(body.provisioning_token);
    if (claims.tenant_id !== body.tenant_id) throw forbidden('Context Violation: provisioning token tenant mismatch');
    if (await isTenantSuspended(body.tenant_id)) throw forbidden('Tenant is suspended');
    const tenantId = body.tenant_id;
    const hw = body.hardware_identifiers;
    const md = body.device_metadata;

    const result = await withTenant(tenantId, async (tx) => {
      // Rule 2 — explicit global IMEI collision check (SECURITY DEFINER)
      for (const candidate of [hw.imei_1, hw.imei_2].filter(Boolean) as string[]) {
        const { rows } = await tx.query('SELECT * FROM check_imei_availability($1)', [candidate]);
        if (rows[0]?.is_enrolled && rows[0].owning_tenant_id !== tenantId) {
          throw conflict('IMEI is already enrolled under another tenant', 'IMEI_ALREADY_ENROLLED');
        }
      }
      const existing = await tx.query('SELECT * FROM devices WHERE imei = $1 OR imei = $2 OR imei_2 = $1 FOR UPDATE', [
        hw.imei_1,
        hw.imei_2 ?? hw.imei_1,
      ]);
      let device = existing.rows[0];
      let firstEnrollment = false;
      if (device) {
        if (device.state === 'RELEASED') throw conflict('Device has been released from financing', 'DEVICE_RELEASED');
        // Pre-registered at POS (PENDING_ENROLLMENT) -> ACTIVE. Re-enrollment after a
        // factory reset keeps the current (possibly locked) state — hard-reset survival.
        const state: DeviceState = device.state === 'PENDING_ENROLLMENT' ? nextState('PENDING_ENROLLMENT', 'ENROLL') : device.state;
        firstEnrollment = device.state === 'PENDING_ENROLLMENT';
        const upd = await tx.query(
          `UPDATE devices SET imei_2 = $2, serial_number = $3, mac_address = $4, manufacturer = $5, model = $6,
                  android_version = $7, api_level = $8, build_fingerprint = $9, knox_sdk_version = $10, fcm_token = COALESCE($11, fcm_token),
                  state = $12, enrolled_at = COALESCE(enrolled_at, NOW()), last_heartbeat_at = NOW(), heartbeat_lost_notified = false,
                  updated_at = NOW()
            WHERE id = $1 RETURNING *`,
          [device.id, hw.imei_2 ?? null, hw.serial_number ?? null, hw.mac_address ?? null, md.manufacturer, md.model,
           md.android_version, md.api_level, md.build_fingerprint ?? null, md.knox_sdk_version ?? null, body.fcm_token ?? null, state],
        );
        device = upd.rows[0];
      } else {
        firstEnrollment = true;
        const ins = await tx.query(
          `INSERT INTO devices (tenant_id, imei, imei_2, serial_number, mac_address, manufacturer, model, android_version, api_level,
                                build_fingerprint, knox_sdk_version, fcm_token, state, enrolled_at, last_heartbeat_at)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,'ACTIVE',NOW(),NOW()) RETURNING *`,
          [tenantId, hw.imei_1, hw.imei_2 ?? null, hw.serial_number ?? null, hw.mac_address ?? null, md.manufacturer, md.model,
           md.android_version, md.api_level, md.build_fingerprint ?? null, md.knox_sdk_version ?? null, body.fcm_token ?? null],
        );
        device = ins.rows[0];
      }
      if (firstEnrollment) {
        await emitWebhookEvent(tenantId, 'device.enrolled', {
          device_id: device.id, imei: device.imei, imei_2: device.imei_2, serial_number: device.serial_number,
          model: device.model, manufacturer: device.manufacturer, borrower_id: device.borrower_id,
        }, tx);
      }
      return device;
    });

    await writeSnapshot(tenantId, result.id, result.state, result.lock_status);
    const base = config.publicApiUrl.replace(/\/$/, '');
    res.status(201).json({
      status: 'ENROLLED',
      device_id: result.id,
      tenant_id: tenantId,
      enrolled_at: new Date(result.enrolled_at).toISOString(),
      device_jwt: tokens.signDevice({ sub: result.id, tenant_id: tenantId }),
      current_state: result.state,
      offline_unlock_secret: result.offline_secret,
      offline_unlock_window_seconds: config.offlineUnlockWindowSeconds,
      endpoints: {
        config_url: `${base}/api/v1/tenants/${tenantId}/config`,
        heartbeat_url: `${base}/api/v1/devices/${result.id}/heartbeat`,
        ack_url_template: `${base}/api/v1/devices/${result.id}/commands/{command_id}/ack`,
      },
      mqtt: config.mqttUrl ? { url: config.mqttUrl, topic: deviceTopic(tenantId, result.id) } : null,
    });
  }),
);

const stateEnum = z.enum(DEVICE_STATES);

export const heartbeatSchema = z.object({
  reported_state: stateEnum,
  battery_level: z.number().int().min(0).max(100).optional(),
  charging: z.boolean().optional(),
  network_type: z.string().max(32).optional(),
  location: z.object({ lat: z.number(), lng: z.number(), accuracy_m: z.number().optional() }).optional().nullable(),
  integrity: z
    .object({
      rooted: z.boolean().default(false),
      bootloader_unlocked: z.boolean().default(false),
      adb_enabled: z.boolean().default(false),
      developer_options_enabled: z.boolean().default(false),
      unauthorized_binaries: z.array(z.string()).default([]),
      verified_boot_state: z.string().optional(),
    })
    .optional(),
  fcm_token: z.string().optional().nullable(),
  config_version: z.number().int().optional(),
  app_version: z.string().optional(),
  acked_commands: z.array(z.object({ command_id: z.string(), applied_state: stateEnum })).default([]),
  offline_unlocks: z.array(z.object({ window: z.number().int(), used_at: z.string() })).default([]),
});

/** POST /api/v1/devices/{device_id}/heartbeat — Device JWT */
devicesRouter.post(
  '/api/v1/devices/:device_id/heartbeat',
  requireDevice(),
  rateLimit('heartbeat', 600, 60),
  ah(async (req, res) => {
    const body = heartbeatSchema.parse(req.body);
    const tenantId = req.tenantId!;
    const deviceId = param(req, 'device_id');
    const out = await withTenant(tenantId, async (tx) => {
      let device = await getDeviceForUpdate(tx, deviceId);
      if (!device) throw notFound('Device not found');

      for (const ack of body.acked_commands) {
        if (isUuid(ack.command_id)) await acknowledgeCommand(tx, tenantId, device, ack.command_id, ack.applied_state);
      }
      device = (await getDeviceForUpdate(tx, deviceId))!;

      const mismatch = body.reported_state !== device.state ? device.state_mismatch_count + 1 : 0;
      const integrity = body.integrity;
      const tampered = !!integrity && (integrity.rooted || integrity.bootloader_unlocked || integrity.adb_enabled || integrity.unauthorized_binaries.length > 0);
      const newlyTampered = tampered && !device.is_tampered;

      await tx.query(
        `UPDATE devices SET last_heartbeat_at = NOW(), heartbeat_lost_notified = false, battery_level = COALESCE($2, battery_level),
                last_location = COALESCE($3, last_location), reported_state = $4, state_mismatch_count = $5,
                fcm_token = COALESCE($6, fcm_token), is_tampered = is_tampered OR $7,
                tamper_details = CASE WHEN $7 THEN $8::jsonb ELSE tamper_details END, updated_at = NOW()
          WHERE id = $1`,
        [deviceId, body.battery_level ?? null, body.location ? JSON.stringify(body.location) : null, body.reported_state, mismatch,
         body.fcm_token ?? null, tampered, JSON.stringify(integrity ?? {})],
      );

      for (const u of body.offline_unlocks) {
        await tx.query(
          `INSERT INTO device_lock_events (tenant_id, device_id, event_type, status, reason, payload)
           VALUES ($1, $2, 'OFFLINE_UNLOCK_ISSUED', 'acknowledged', 'OFFLINE_CODE_REDEEMED', $3)`,
          [tenantId, deviceId, JSON.stringify(u)],
        );
      }

      if (newlyTampered) {
        await tx.query(
          `INSERT INTO device_lock_events (tenant_id, device_id, event_type, status, reason, payload)
           VALUES ($1, $2, 'TAMPER_ALERT', 'acknowledged', 'INTEGRITY_CHECK_FAILED', $3)`,
          [tenantId, deviceId, JSON.stringify(integrity)],
        );
        await emitWebhookEvent(tenantId, 'device.tamper_detected', { device_id: deviceId, imei: device.imei, integrity }, tx);
      }

      const pending = await tx.query(
        `SELECT id, payload->>'action' AS action, target_state FROM device_lock_events
          WHERE device_id = $1 AND status IN ('queued', 'sent') AND target_state = $2 AND event_type LIKE '%_COMMAND'
          ORDER BY created_at DESC LIMIT 1`,
        [deviceId, device.state],
      );
      const cfg = await loadConfigTx(tx, tenantId);
      const loan = await getLoanSummary(tx, deviceId);
      return { device, pending: pending.rows[0], cfg, loan, newlyTampered };
    });

    if (out.newlyTampered) {
      await enqueueDeviceCommand(
        { tenantId, deviceId, action: 'LOCK', reason: 'TAMPER_DETECTED', source: 'telemetry' },
        'tamper',
      );
    }

    const base = config.publicApiUrl.replace(/\/$/, '');
    res.json({
      server_time: new Date().toISOString(),
      device_id: deviceId,
      desired_state: out.device.state,
      lock_status: out.device.lock_status,
      pending_command: out.pending ? { command_id: out.pending.id, action: out.pending.action, target_state: out.pending.target_state } : null,
      config_version: out.cfg.version,
      config_url: out.cfg.version !== body.config_version ? `${base}/api/v1/tenants/${tenantId}/config` : null,
      heartbeat_interval_seconds: config.heartbeatIntervalSeconds,
      loan: out.loan,
      policy_overrides: {
        offline_lock_fallback: out.cfg.enforcement_policies.offline_lock_fallback,
        whitelisted_packages_when_locked: out.cfg.enforcement_policies.whitelisted_packages_when_locked,
      },
    });
  }),
);

/** POST /api/v1/devices/{device_id}/commands/{command_id}/ack — Device JWT */
devicesRouter.post(
  '/api/v1/devices/:device_id/commands/:command_id/ack',
  requireDevice(),
  ah(async (req, res) => {
    const { applied_state } = z.object({ applied_state: stateEnum }).parse(req.body);
    const commandId = param(req, 'command_id');
    if (!isUuid(commandId)) throw new HttpError(400, 'Invalid command id');
    const tenantId = req.tenantId!;
    const ok = await withTenant(tenantId, async (tx) => {
      const device = await getDeviceForUpdate(tx, param(req, 'device_id'));
      if (!device) throw notFound('Device not found');
      return acknowledgeCommand(tx, tenantId, device, commandId, applied_state);
    });
    if (!ok) throw notFound('Command not found or already acknowledged');
    res.json({ status: 'ACKNOWLEDGED', command_id: commandId });
  }),
);
