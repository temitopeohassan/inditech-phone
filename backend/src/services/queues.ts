import { Queue, type JobsOptions } from 'bullmq';
import { createBullConnection } from '../lib/redis.js';
import type { CommandAction } from '../domain/stateMachine.js';

/** Queue topology (see docs: Background Worker & Webhook Processing Architecture). */
export const QUEUES = {
  repaymentWebhooks: 'repayment-webhooks',
  overdueEvaluator: 'overdue-evaluator',
  deviceCommands: 'device-commands',
  fcmDispatcher: 'fcm-dispatcher',
  webhookDelivery: 'webhook-delivery',
  heartbeatReconciliation: 'heartbeat-reconciliation',
  deadLetter: 'dlq-dead-letter-queue',
} as const;
export type QueueName = (typeof QUEUES)[keyof typeof QUEUES];

export const QUEUE_SETTINGS: Record<QueueName, { concurrency: number; defaults: JobsOptions }> = {
  'repayment-webhooks': { concurrency: 50, defaults: { attempts: 5, backoff: { type: 'exponential', delay: 1000 }, priority: 2 } },
  'overdue-evaluator': { concurrency: 5, defaults: { attempts: 3, backoff: { type: 'fixed', delay: 5000 }, priority: 3 } },
  'device-commands': { concurrency: 20, defaults: { attempts: 5, backoff: { type: 'exponential', delay: 2000 }, priority: 1 } },
  'fcm-dispatcher': { concurrency: 100, defaults: { attempts: 3, backoff: { type: 'exponential', delay: 500 }, priority: 2 } },
  'webhook-delivery': { concurrency: 25, defaults: { attempts: 8, backoff: { type: 'exponential', delay: 2000 } } },
  'heartbeat-reconciliation': { concurrency: 2, defaults: { attempts: 3, backoff: { type: 'fixed', delay: 5000 } } },
  'dlq-dead-letter-queue': { concurrency: 1, defaults: { attempts: 1 } },
};

const queues = new Map<QueueName, Queue>();

export function queue(name: QueueName): Queue {
  let q = queues.get(name);
  if (!q) {
    q = new Queue(name, {
      connection: createBullConnection(),
      defaultJobOptions: { removeOnComplete: 1000, removeOnFail: 5000, ...QUEUE_SETTINGS[name].defaults },
    });
    queues.set(name, q);
  }
  return q;
}

export async function closeQueues(): Promise<void> {
  await Promise.all([...queues.values()].map((q) => q.close()));
  queues.clear();
}

// ---- Job payloads -------------------------------------------------------

export interface DeviceCommandJob {
  tenantId: string;
  deviceId: string;
  action: CommandAction;
  reason: string;
  loanId?: string | null;
  source: 'api' | 'portal' | 'lender_webhook' | 'repayment' | 'overdue_evaluator' | 'reconciliation' | 'telemetry';
}

export interface FcmJob {
  tenantId: string;
  deviceId: string;
  fcmToken: string;
  action: CommandAction;
  commandId: string;
  targetState: string;
}

export interface RepaymentJob {
  tenantId: string;
  idempotencyKey: string;
  transactionId: string;
  loanId?: string;
  accountRef?: string;
  amount: number;
  paymentMethod: string;
}

export interface WebhookDeliveryJob {
  tenantId: string;
  subscriptionId: string;
  event: string;
  eventId: string;
  occurredAt: string;
  data: Record<string, unknown>;
}

export interface OverdueJob {
  tenantId: string;
}

/** Builds a BullMQ custom job id (BullMQ forbids ':' in custom ids). */
export function jobId(...parts: (string | number)[]): string {
  return parts.map((p) => String(p).replace(/[^A-Za-z0-9_.-]/g, '_')).join('__');
}

/** Idempotent job id for a device command: identical commands in flight collapse into one. */
export function deviceCommandJobId(job: DeviceCommandJob, dedupeKey: string): string {
  return jobId('cmd', job.deviceId, job.action, dedupeKey);
}

export async function enqueueDeviceCommand(job: DeviceCommandJob, dedupeKey: string = String(Date.now())): Promise<string> {
  const jobId = deviceCommandJobId(job, dedupeKey);
  const name = `${job.action}_DEVICE`;
  await queue(QUEUES.deviceCommands).add(name, job, { jobId });
  return jobId;
}
