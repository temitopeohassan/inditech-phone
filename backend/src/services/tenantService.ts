import { globalQuery, withTenant, type Tx } from '../db/pool.js';
import { keys, redis } from '../lib/redis.js';
import { notFound } from '../lib/errors.js';
import { tenantConfigSchema, type TenantConfig, DEFAULT_SOFT_LOCK_HOURS } from '../domain/tenantConfig.js';

export interface TenantRow {
  id: string;
  name: string;
  slug: string;
  status: 'active' | 'suspended' | 'deactivated';
  custom_domain: string | null;
  inbound_webhook_secret: string;
}

export async function getTenant(tenantId: string): Promise<TenantRow> {
  const { rows } = await globalQuery<TenantRow>('SELECT * FROM tenants WHERE id = $1', [tenantId]);
  if (!rows[0]) throw notFound('Tenant not found');
  return rows[0];
}

export async function getTenantBySlug(slug: string): Promise<TenantRow | undefined> {
  const { rows } = await globalQuery<TenantRow>('SELECT * FROM tenants WHERE slug = $1', [slug]);
  return rows[0];
}

export async function listActiveTenants(): Promise<TenantRow[]> {
  const { rows } = await globalQuery<TenantRow>(`SELECT * FROM tenants WHERE status = 'active'`);
  return rows;
}

/** Reads the tenant config inside an existing RLS transaction. */
export async function loadConfigTx(tx: Tx, tenantId: string): Promise<TenantConfig> {
  const { rows } = await tx.query<{ config: TenantConfig; version: number }>(
    'SELECT config, version FROM tenant_configs WHERE tenant_id = $1',
    [tenantId],
  );
  if (!rows[0]) throw notFound('Tenant configuration not found');
  return { ...rows[0].config, tenant_id: tenantId, version: rows[0].version };
}

/** Cached config fetch for hot paths (device config pulls, heartbeats). */
export async function getTenantConfig(tenantId: string): Promise<TenantConfig> {
  try {
    const cached = await redis().get(keys.tenantConfig(tenantId));
    if (cached) return JSON.parse(cached) as TenantConfig;
  } catch {
    /* cache miss */
  }
  const cfg = await withTenant(tenantId, (tx) => loadConfigTx(tx, tenantId));
  await redis().set(keys.tenantConfig(tenantId), JSON.stringify(cfg), 'EX', 300).catch(() => undefined);
  return cfg;
}

export async function saveConfigTx(tx: Tx, cfg: TenantConfig): Promise<TenantConfig> {
  const valid = tenantConfigSchema.parse(cfg);
  await tx.query(
    `INSERT INTO tenant_configs (tenant_id, version, config, updated_at) VALUES ($1, $2, $3, NOW())
     ON CONFLICT (tenant_id) DO UPDATE SET version = EXCLUDED.version, config = EXCLUDED.config, updated_at = NOW()`,
    [valid.tenant_id, valid.version, JSON.stringify(valid)],
  );
  return valid;
}

export async function invalidateConfigCache(tenantId: string): Promise<void> {
  await redis().del(keys.tenantConfig(tenantId)).catch(() => undefined);
}

export function enforcementHours(cfg: TenantConfig) {
  const p = cfg.enforcement_policies;
  return {
    warningHoursBeforeLock: p.warning_overlay_hours_before_lock,
    gracePeriodHours: p.grace_period_hours,
    softLockHours: p.soft_lock_hours ?? DEFAULT_SOFT_LOCK_HOURS,
  };
}

/** Syncs Redis control flags (kill-switch + custom domain) from the tenants table. */
export async function syncTenantFlags(t: TenantRow): Promise<void> {
  const r = redis();
  if (t.status === 'active') await r.del(keys.tenantStatus(t.id));
  else await r.set(keys.tenantStatus(t.id), t.status.toUpperCase());
  if (t.custom_domain) await r.set(keys.tenantDomain(t.id), t.custom_domain);
  else await r.del(keys.tenantDomain(t.id));
}

export async function isTenantSuspended(tenantId: string): Promise<boolean> {
  const status = await redis().get(keys.tenantStatus(tenantId));
  return status === 'SUSPENDED' || status === 'DEACTIVATED';
}
