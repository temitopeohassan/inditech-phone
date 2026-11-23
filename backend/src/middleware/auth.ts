import type { NextFunction, Request, Response } from 'express';
import { config } from '../config.js';
import { forbidden, unauthorized } from '../lib/errors.js';
import { tokens, type AdminClaims, type DeviceClaims, type Role } from '../lib/jwt.js';
import { keys, redis } from '../lib/redis.js';
import { safeEqual } from '../lib/crypto.js';

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Express {
    interface Request {
      tenantId?: string;
      admin?: AdminClaims;
      device?: DeviceClaims;
      rawBody?: Buffer;
    }
  }
}

function bearer(req: Request): string {
  const header = req.headers.authorization;
  if (!header) throw unauthorized('Missing Authorization Header');
  const [scheme, token] = header.split(' ');
  if (scheme?.toLowerCase() !== 'bearer' || !token) throw unauthorized('Invalid or Expired Bearer Token');
  return token;
}

/**
 * Tenant Context Enforcement: validates the Host header against the tenant's
 * custom domain cache. The shared platform API host is always accepted.
 */
async function enforceTenantHost(req: Request, tenantId: string): Promise<void> {
  const host = req.headers.host;
  if (!host || host === config.platformApiHost) return;
  const cached = await redis().get(keys.tenantDomain(tenantId));
  if (cached && cached !== host) throw forbidden('Tenant Domain Host Mismatch');
}

async function enforceNotSuspended(tenantId: string): Promise<void> {
  const status = await redis().get(keys.tenantStatus(tenantId));
  if (status === 'SUSPENDED') throw forbidden('Tenant is suspended');
}

/** Admin portal / lender API bearer tokens. Optionally restricts roles. */
export function requireAdmin(...roles: Role[]) {
  return async (req: Request, _res: Response, next: NextFunction) => {
    try {
      const claims = tokens.verifyAdmin(bearer(req));
      if (roles.length && !roles.includes(claims.role)) throw forbidden('Insufficient role');
      const pathTenant = req.params.tenant_id;
      if (pathTenant && pathTenant !== claims.tenant_id) throw forbidden('Context Violation: tenant mismatch');
      await enforceTenantHost(req, claims.tenant_id);
      req.admin = claims;
      req.tenantId = claims.tenant_id;
      next();
    } catch (err) {
      next(err);
    }
  };
}

/** Device JWT issued at enrollment. Device id in the path must match the token subject. */
export function requireDevice() {
  return async (req: Request, _res: Response, next: NextFunction) => {
    try {
      const claims = tokens.verifyDevice(bearer(req));
      const pathDevice = req.params.device_id;
      const pathTenant = req.params.tenant_id;
      if (pathDevice && pathDevice !== claims.sub) throw forbidden('Context Violation: device mismatch');
      if (pathTenant && pathTenant !== claims.tenant_id) throw forbidden('Context Violation: tenant mismatch');
      await enforceNotSuspended(claims.tenant_id);
      req.device = claims;
      req.tenantId = claims.tenant_id;
      next();
    } catch (err) {
      next(err);
    }
  };
}

/** Platform Super-Admin API key (X-Platform-Key). */
export function requirePlatformAdmin() {
  return (req: Request, _res: Response, next: NextFunction) => {
    const key = req.headers['x-platform-key'];
    if (typeof key !== 'string' || !safeEqual(key, config.platformAdminKey)) return next(unauthorized('Invalid platform key'));
    next();
  };
}
