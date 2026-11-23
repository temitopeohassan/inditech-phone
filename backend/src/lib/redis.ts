import { Redis } from 'ioredis';
import { config } from '../config.js';

let client: Redis | undefined;

/** Shared Redis connection for caching, idempotency keys, mutexes and kill-switch flags. */
export function redis(): Redis {
  if (!client) {
    client = new Redis(config.redisUrl, { maxRetriesPerRequest: null, lazyConnect: false });
  }
  return client;
}

/** BullMQ needs dedicated connections with maxRetriesPerRequest = null. */
export function createBullConnection(): Redis {
  return new Redis(config.redisUrl, { maxRetriesPerRequest: null });
}

export async function closeRedis(): Promise<void> {
  if (client) {
    await client.quit();
    client = undefined;
  }
}

/** Rule 3 — every secondary-storage key is prefixed with the tenant id. */
export const keys = {
  tenantStatus: (tenantId: string) => `tenant:status:${tenantId}`,
  tenantDomain: (tenantId: string) => `tenant_domain:${tenantId}`,
  tenantConfig: (tenantId: string) => `tenant:${tenantId}:config`,
  deviceSnapshot: (tenantId: string, deviceId: string) => `tenant:${tenantId}:device:${deviceId}:snapshot`,
  deviceMutex: (deviceId: string) => `lock:device:${deviceId}`,
  webhookIdempotency: (tenantId: string, key: string) => `idempotency:webhook:${tenantId}:${key}`,
  rateLimit: (bucket: string, id: string, window: number) => `ratelimit:${bucket}:${id}:${window}`,
};
