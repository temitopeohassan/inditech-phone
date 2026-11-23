import express from 'express';
import { devicesRouter } from './routes/devices.js';
import { tenantsRouter } from './routes/tenants.js';
import { adminRouter } from './routes/admin.js';
import { webhooksRouter } from './routes/webhooks.js';
import { platformRouter } from './routes/platform.js';
import { errorHandler } from './middleware/errors.js';
import { db } from './db/pool.js';
import { redis } from './lib/redis.js';

export function createApp() {
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', true);

  // Keep the raw body for HMAC signature verification of inbound webhooks.
  app.use(
    express.json({
      limit: '2mb',
      verify: (req, _res, buf) => {
        (req as express.Request).rawBody = Buffer.from(buf);
      },
    }),
  );
  app.use(express.text({ type: ['text/csv', 'text/plain'], limit: '10mb' }));

  app.get('/healthz', (_req, res) => {
    res.json({ status: 'ok' });
  });
  app.get('/readyz', async (_req, res) => {
    try {
      await db().query('SELECT 1');
      await redis().ping();
      res.json({ status: 'ready' });
    } catch (err) {
      res.status(503).json({ status: 'unavailable', error: (err as Error).message });
    }
  });

  app.use(platformRouter);
  app.use(devicesRouter);
  app.use(tenantsRouter);
  app.use(webhooksRouter);
  app.use(adminRouter);

  app.use((_req, res) => {
    res.status(404).json({ error: { code: 'NOT_FOUND', message: 'Route not found' } });
  });
  app.use(errorHandler);
  return app;
}
