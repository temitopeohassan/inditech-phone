import { createApp } from './app.js';
import { config } from './config.js';
import { logger } from './lib/logger.js';
import { closeDb } from './db/pool.js';
import { closeRedis } from './lib/redis.js';
import { closeQueues } from './services/queues.js';

const server = createApp().listen(config.port, () => logger.info({ port: config.port }, 'device platform API listening'));

async function shutdown(signal: string) {
  logger.info({ signal }, 'shutting down');
  server.close();
  await Promise.allSettled([closeQueues(), closeDb(), closeRedis()]);
  process.exit(0);
}
process.on('SIGTERM', () => void shutdown('SIGTERM'));
process.on('SIGINT', () => void shutdown('SIGINT'));
