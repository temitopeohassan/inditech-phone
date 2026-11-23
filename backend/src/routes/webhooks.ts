import { Router } from 'express';
import { z } from 'zod';
import { withTenant, isUuid } from '../db/pool.js';
import { ah, param } from '../lib/http.js';
import { HttpError, badRequest, notFound, unauthorized } from '../lib/errors.js';
import { hmacSha256Hex, safeEqual, sha256Hex } from '../lib/crypto.js';
import { keys, redis } from '../lib/redis.js';
import { logger } from '../lib/logger.js';
import { getTenant } from '../services/tenantService.js';
import { QUEUES, enqueueDeviceCommand, jobId, queue, type RepaymentJob } from '../services/queues.js';

export const webhooksRouter = Router();

async function verifyInboundSignature(tenantId: string, raw: Buffer | undefined, signature: string | undefined) {
  if (!isUuid(tenantId)) throw notFound('Tenant not found');
  const tenant = await getTenant(tenantId);
  if (!raw) throw badRequest('Missing body');
  const expected = hmacSha256Hex(tenant.inbound_webhook_secret, raw);
  if (!safeEqual(signature, expected)) throw unauthorized('Invalid payload signature');
  return tenant;
}

/** Claims an idempotency key for 24h. Returns false if it was already claimed. */
async function claimIdempotency(tenantId: string, key: string): Promise<boolean> {
  const res = await redis().set(keys.webhookIdempotency(tenantId, key), 'PROCESSING', 'EX', 86_400, 'NX');
  return res === 'OK';
}

const lockStateSchema = z
  .object({
    action: z.enum(['LOCK', 'UNLOCK', 'WARN', 'SOFT_LOCK', 'RELEASE']),
    device_id: z.string().refine(isUuid).optional(),
    imei: z.string().optional(),
    account_ref: z.string().optional(),
    reason: z.string().max(128).default('LENDER_REQUEST'),
    idempotency_key: z.string().min(1).max(128).optional(),
  })
  .refine((b) => b.device_id || b.imei || b.account_ref, 'device_id, imei or account_ref is required');

/** POST /api/v1/webhooks/lenders/{tenant_id}/lock-state — Webhook Signature (x-webhook-signature) */
webhooksRouter.post(
  '/api/v1/webhooks/lenders/:tenant_id/lock-state',
  ah(async (req, res) => {
    const tenantId = param(req, 'tenant_id');
    await verifyInboundSignature(tenantId, req.rawBody, req.headers['x-webhook-signature'] as string | undefined);
    const body = lockStateSchema.parse(req.body);
    const idemKey = body.idempotency_key ?? sha256Hex(req.rawBody!);
    if (!(await claimIdempotency(tenantId, `lock:${idemKey}`))) {
      res.status(200).json({ status: 'ACCEPTED_DUPLICATE' });
      return;
    }
    try {
      const deviceId = await withTenant(tenantId, async (tx) => {
        if (body.device_id) return (await tx.query('SELECT id FROM devices WHERE id = $1', [body.device_id])).rows[0]?.id;
        if (body.imei) return (await tx.query('SELECT id FROM devices WHERE imei = $1 OR imei_2 = $1', [body.imei])).rows[0]?.id;
        return (await tx.query('SELECT device_id AS id FROM loans WHERE account_ref = $1', [body.account_ref])).rows[0]?.id;
      });
      if (!deviceId) throw notFound('Device not found');
      const jobId = await enqueueDeviceCommand(
        { tenantId, deviceId, action: body.action, reason: body.reason, source: 'lender_webhook' },
        idemKey,
      );
      res.status(202).json({ status: 'ACCEPTED', device_id: deviceId, job_id: jobId });
    } catch (err) {
      await redis().del(keys.webhookIdempotency(tenantId, `lock:${idemKey}`));
      throw err;
    }
  }),
);

const paymentSchema = z.object({
  transactionId: z.string().min(1).max(128),
  loanId: z.string().refine(isUuid).optional(),
  accountRef: z.string().optional(),
  amount: z.coerce.number().positive(),
  idempotencyKey: z.string().min(1).max(128).optional(),
  paymentMethod: z.string().max(64).default('PROVIDER_WEBHOOK'),
}).refine((b) => b.loanId || b.accountRef, 'loanId or accountRef is required');

/**
 * POST /api/v1/webhooks/payments/{tenant_id} — payment provider callback (x-provider-signature).
 * HMAC verification + idempotency happen inline; the ledger update runs on the
 * repayment-webhooks queue.
 */
webhooksRouter.post(
  '/api/v1/webhooks/payments/:tenant_id',
  ah(async (req, res) => {
    const tenantId = param(req, 'tenant_id');
    await verifyInboundSignature(tenantId, req.rawBody, req.headers['x-provider-signature'] as string | undefined);
    const body = paymentSchema.parse(req.body);
    const idempotencyKey = body.idempotencyKey ?? `${body.transactionId}:${sha256Hex(req.rawBody!)}`;
    if (!(await claimIdempotency(tenantId, idempotencyKey))) {
      res.status(200).json({ status: 'ACCEPTED_DUPLICATE' });
      return;
    }
    const job: RepaymentJob = {
      tenantId,
      idempotencyKey,
      transactionId: body.transactionId,
      loanId: body.loanId,
      accountRef: body.accountRef,
      amount: body.amount,
      paymentMethod: body.paymentMethod,
    };
    try {
      await queue(QUEUES.repaymentWebhooks).add('PROCESS_REPAYMENT', job, { jobId: jobId('repay', tenantId, body.transactionId) });
    } catch (err) {
      await redis().del(keys.webhookIdempotency(tenantId, idempotencyKey)); // allow provider retry
      logger.error({ err }, 'failed to enqueue repayment');
      throw new HttpError(500, 'Internal processing failure');
    }
    res.status(202).json({ status: 'ACCEPTED' });
  }),
);
