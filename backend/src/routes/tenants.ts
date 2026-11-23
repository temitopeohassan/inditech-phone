import { Router } from 'express';
import { ah, param } from '../lib/http.js';
import { forbidden, unauthorized } from '../lib/errors.js';
import { tokens } from '../lib/jwt.js';
import { rateLimit } from '../middleware/rateLimit.js';
import { getTenantConfig, isTenantSuspended } from '../services/tenantService.js';

export const tenantsRouter = Router();

/**
 * GET /api/v1/tenants/{tenant_id}/config
 * Device JWT (or the provisioning token during first boot, before enrollment
 * completes — step 3 of the enrollment lifecycle). Supports ETag caching.
 */
tenantsRouter.get(
  '/api/v1/tenants/:tenant_id/config',
  rateLimit('config', 300, 60),
  ah(async (req, res) => {
    const tenantId = param(req, 'tenant_id');
    const header = req.headers.authorization ?? '';
    const token = header.startsWith('Bearer ') ? header.slice(7) : '';
    if (!token) throw unauthorized('Missing Authorization Header');
    let claimsTenant: string;
    try {
      claimsTenant = tokens.verifyDevice(token).tenant_id;
    } catch {
      claimsTenant = tokens.verifyProvisioning(token).tenant_id;
    }
    if (claimsTenant !== tenantId) throw forbidden('Context Violation: tenant mismatch');
    if (await isTenantSuspended(tenantId)) throw forbidden('Tenant is suspended');

    const cfg = await getTenantConfig(tenantId);
    const etag = `"cfg-${tenantId}-v${cfg.version}"`;
    res.setHeader('ETag', etag);
    res.setHeader('Cache-Control', 'private, max-age=60');
    if (req.headers['if-none-match'] === etag) {
      res.status(304).end();
      return;
    }
    res.json(cfg);
  }),
);
