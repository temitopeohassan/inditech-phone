import { Worker, type Job } from 'bullmq';
import { config } from './config.js';
import { logger } from './lib/logger.js';
import { createBullConnection, closeRedis } from './lib/redis.js';
import { closeDb } from './db/pool.js';
import { closeMessaging } from './services/messaging.js';
import { deliverWebhook } from './services/outboundWebhooks.js';
import { QUEUES, QUEUE_SETTINGS, closeQueues, queue, type QueueName } from './services/queues.js';
import {
  processDeviceCommand,
  processFcm,
  processHeartbeatReconciliation,
  processOverdue,
  processRepayment,
  scheduleOverdueForAllTenants,
  scheduleReconciliationForAllTenants,
} from './workers/processors.js';

type Processor = (job: Job) => Promise<unknown>;

const processors: Partial<Record<QueueName, Processor>> = {
  [QUEUES.deviceCommands]: (job) => processDeviceCommand(job.data, job.id!),
  [QUEUES.fcmDispatcher]: (job) => processFcm(job.data),
  [QUEUES.repaymentWebhooks]: (job) => processRepayment(job.data),
  [QUEUES.webhookDelivery]: (job) => deliverWebhook(job.data),
  [QUEUES.overdueEvaluator]: (job) => (job.name === 'SCHEDULE_ALL' ? scheduleOverdueForAllTenants() : processOverdue(job.data)),
  [QUEUES.heartbeatReconciliation]: (job) =>
    job.name === 'SCHEDULE_ALL' ? scheduleReconciliationForAllTenants() : processHeartbeatReconciliation(job.data.tenantId),
};

const workers: Worker[] = [];

for (const [name, processor] of Object.entries(processors) as [QueueName, Processor][]) {
  const worker = new Worker(name, processor, { connection: createBullConnection(), concurrency: QUEUE_SETTINGS[name].concurrency });

  // Dead-letter queue: jobs that exhausted their retries are parked for manual inspection.
  worker.on('failed', async (job, err) => {
    if (!job) return;
    const max = job.opts.attempts ?? 1;
    if (job.attemptsMade >= max) {
      logger.error({ queue: name, jobId: job.id, err: err.message }, `DLQ Alert: Job ${job.id} in queue ${name} failed critically`);
      await queue(QUEUES.deadLetter)
        .add('DEAD_LETTER', { queue: name, jobId: job.id, name: job.name, data: job.data, error: err.message, failedAt: new Date().toISOString() })
        .catch(() => undefined);
    } else {
      logger.warn({ queue: name, jobId: job.id, attempt: job.attemptsMade, err: err.message }, 'job failed, will retry');
    }
  });
  worker.on('error', (err) => logger.error({ queue: name, err: err.message }, 'worker error'));
  workers.push(worker);
}

// Cron-driven fan-out jobs
await queue(QUEUES.overdueEvaluator).upsertJobScheduler('overdue-cron', { pattern: config.cron.overdueEvaluator }, { name: 'SCHEDULE_ALL', data: {} });
await queue(QUEUES.heartbeatReconciliation).upsertJobScheduler(
  'reconciliation-cron',
  { pattern: config.cron.heartbeatReconciliation },
  { name: 'SCHEDULE_ALL', data: {} },
);

logger.info({ queues: Object.keys(processors) }, 'workers started');

async function shutdown() {
  await Promise.allSettled(workers.map((w) => w.close()));
  await Promise.allSettled([closeQueues(), closeMessaging(), closeDb(), closeRedis()]);
  process.exit(0);
}
process.on('SIGTERM', () => void shutdown());
process.on('SIGINT', () => void shutdown());
