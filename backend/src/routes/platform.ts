import { Router } from 'express';
import bcrypt from 'bcryptjs';
import { z } from 'zod';
import { globalQuery, withTenant, isUuid } from '../db/pool.js';
import { ah, param } from '../lib/http.js';
import { conflict, notFound } from '../lib/errors.js';
import { requirePlatformAdmin } from '../middleware/auth.js';
import { defaultTenantConfig, deepMerge, tenantConfigSchema } from '../domain/tenantConfig.js';
import { saveConfigTx, syncTenantFlags, invalidateConfigCache, type TenantRow } from '../services/tenantService.js';

export const platformRouter = Router();
platformRouter.use('/api/v1/platform', requirePlatformAdmin());

const createTenantSchema = z.object({
  name: z.string().min(2).max(255),
  slug: z.string().regex(/^[a-z0-9][a-z0-9-]{1,62}$/),
  custom_domain: z.string().max(255).optional(),
  admin_email: z.string().email(),
  admin_password: z.string().min(10),
  config: z.record(z.string(), z.unknown()).optional(),
});

/** Provisions a fully isolated tenant (tenant row, branding config, first admin) in one call. */
platformRouter.post(
  '/api/v1/platform/tenants',
  ah(async (req, res) => {
    const body = createTenantSchema.parse(req.body);
    let tenant: TenantRow;
    try {
      tenant = (
        await globalQuery<TenantRow>('INSERT INTO tenants (name, slug, custom_domain) VALUES ($1, $2, $3) RETURNING *', [
          body.name, body.slug, body.custom_domain ?? null,
        ])
      ).rows[0]!;
    } catch (err: any) {
      if (err.code === '23505') throw conflict('Tenant slug or domain already exists');
      throw err;
    }
    try {
      const cfg = tenantConfigSchema.parse(deepMerge(defaultTenantConfig(tenant.id, body.name), { ...(body.config ?? {}), tenant_id: tenant.id, version: 1 }));
      const hash = await bcrypt.hash(body.admin_password, 12);
      await withTenant(tenant.id, async (tx) => {
        await saveConfigTx(tx, cfg);
        await tx.query(`INSERT INTO tenant_users (tenant_id, email, password_hash, role) VALUES ($1, $2, $3, 'admin')`, [
          tenant.id, body.admin_email.toLowerCase(), hash,
        ]);
      });
    } catch (err) {
      await globalQuery('DELETE FROM tenants WHERE id = $1', [tenant.id]);
      throw err;
    }
    await syncTenantFlags(tenant);
    res.status(201).json({
      id: tenant.id,
      name: tenant.name,
      slug: tenant.slug,
      status: tenant.status,
      custom_domain: tenant.custom_domain,
      inbound_webhook_secret: tenant.inbound_webhook_secret,
    });
  }),
);

platformRouter.get(
  '/api/v1/platform/tenants',
  ah(async (_req, res) => {
    const { rows } = await globalQuery('SELECT id, name, slug, status, custom_domain, created_at FROM tenants ORDER BY created_at');
    res.json({ data: rows });
  }),
);

/** Suspend / reactivate (kill-switch) and custom-domain changes. */
platformRouter.patch(
  '/api/v1/platform/tenants/:tenant_id',
  ah(async (req, res) => {
    const id = param(req, 'tenant_id');
    if (!isUuid(id)) throw notFound('Tenant not found');
    const body = z
      .object({ status: z.enum(['active', 'suspended', 'deactivated']).optional(), custom_domain: z.string().max(255).nullable().optional(), name: z.string().optional() })
      .parse(req.body);
    const { rows } = await globalQuery<TenantRow>(
      `UPDATE tenants SET status = COALESCE($2, status), name = COALESCE($3, name),
              custom_domain = CASE WHEN $4 THEN $5 ELSE custom_domain END, updated_at = NOW()
        WHERE id = $1 RETURNING *`,
      [id, body.status ?? null, body.name ?? null, body.custom_domain !== undefined, body.custom_domain ?? null],
    );
    if (!rows[0]) throw notFound('Tenant not found');
    await syncTenantFlags(rows[0]);
    await invalidateConfigCache(id);
    res.json({ id: rows[0].id, status: rows[0].status, custom_domain: rows[0].custom_domain });
  }),
);
