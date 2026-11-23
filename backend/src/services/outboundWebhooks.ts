import crypto from 'node:crypto';
import { withTenant, type Tx } from '../db/pool.js';
import { hmacSha256Hex } from '../lib/crypto.js';
import { QUEUES, jobId, queue, type WebhookDeliveryJob } from './queues.js';

export const WEBHOOK_EVENTS = [
  'device.enrolled',
  'device.tamper_detected',
  'device.locked',
  'device.unlocked',
  'device.heartbeat_lost',
  'device.state_changed',
  'device.released',
  'repayment.received',
] as const;
export type WebhookEvent = (typeof WEBHOOK_EVENTS)[number];

/**
 * Fans an event out to every active subscription for the tenant. Must be
 * called with an RLS transaction (or opens one).
 */
export async function emitWebhookEvent(
  tenantId: string,
  event: WebhookEvent,
  data: Record<string, unknown>,
  tx?: Tx,
): Promise<number> {
  const run = async (client: Tx) =>
    (
      await client.query<{ id: string }>(
        `SELECT id FROM webhook_subscriptions WHERE is_active = true AND ($1 = ANY(events) OR '*' = ANY(events))`,
        [event],
      )
    ).rows;
  const subs = tx ? await run(tx) : await withTenant(tenantId, run);
  const eventId = `evt_${crypto.randomUUID().replace(/-/g, '')}`;
  const occurredAt = new Date().toISOString();
  for (const s of subs) {
    const job: WebhookDeliveryJob = { tenantId, subscriptionId: s.id, event, eventId, occurredAt, data };
    await queue(QUEUES.webhookDelivery).add(event, job, { jobId: jobId(eventId, s.id) });
  }
  return subs.length;
}

export function signWebhook(secret: string, timestamp: string, body: string): string {
  return hmacSha256Hex(secret, `${timestamp}.${body}`);
}

/** Delivery processor (webhook-delivery queue). */
export async function deliverWebhook(job: WebhookDeliveryJob, fetchImpl: typeof fetch = fetch): Promise<{ status: number }> {
  const sub = await withTenant(job.tenantId, async (tx) => {
    const { rows } = await tx.query<{ url: string; secret: string; is_active: boolean }>(
      'SELECT url, secret, is_active FROM webhook_subscriptions WHERE id = $1',
      [job.subscriptionId],
    );
    return rows[0];
  });
  if (!sub || !sub.is_active) return { status: 0 };
  const body = JSON.stringify({ id: job.eventId, type: job.event, created_at: job.occurredAt, tenant_id: job.tenantId, data: job.data });
  const timestamp = String(Math.floor(Date.now() / 1000));
  const res = await fetchImpl(sub.url, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-platform-event': job.event,
      'x-platform-event-id': job.eventId,
      'x-platform-timestamp': timestamp,
      'x-platform-signature': signWebhook(sub.secret, timestamp, body),
    },
    body,
    signal: AbortSignal.timeout(10_000),
  });
  if (res.status >= 500 || res.status === 429) throw new Error(`Webhook endpoint responded ${res.status}`);
  return { status: res.status };
}
