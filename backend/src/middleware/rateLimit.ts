import type { NextFunction, Request, Response } from 'express';
import { HttpError } from '../lib/errors.js';
import { keys, redis } from '../lib/redis.js';

/**
 * Fixed-window Redis rate limiter keyed per tenant (or client IP when the
 * request is unauthenticated). Fails open if Redis is unavailable.
 */
export function rateLimit(bucket: string, limit: number, windowSeconds: number) {
  return async (req: Request, res: Response, next: NextFunction) => {
    const id = req.tenantId ?? req.ip ?? 'anonymous';
    const window = Math.floor(Date.now() / 1000 / windowSeconds);
    const key = keys.rateLimit(bucket, id, window);
    try {
      const count = await redis().incr(key);
      if (count === 1) await redis().expire(key, windowSeconds);
      res.setHeader('X-RateLimit-Limit', String(limit));
      res.setHeader('X-RateLimit-Remaining', String(Math.max(0, limit - count)));
      if (count > limit) return next(new HttpError(429, 'Rate limit exceeded', 'RATE_LIMITED'));
    } catch {
      // fail open
    }
    next();
  };
}
