import pg from 'pg';
import { config } from '../config.js';

// NUMERIC -> JS number (amounts are bounded to NUMERIC(12,2))
pg.types.setTypeParser(1700, (v: string) => Number(v));

let pool: pg.Pool | undefined;

export function db(): pg.Pool {
  if (!pool) {
    pool = new pg.Pool({ connectionString: config.databaseUrl, max: 20 });
  }
  return pool;
}

export async function closeDb(): Promise<void> {
  if (pool) {
    await pool.end();
    pool = undefined;
  }
}

export type Tx = pg.PoolClient;

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Runs `fn` inside an explicit transaction with the RLS tenant context set
 * transaction-locally (equivalent to `SET LOCAL app.current_tenant_id`), which is
 * safe behind PgBouncer in transaction pooling mode (Rule 1).
 */
export async function withTenant<T>(tenantId: string, fn: (tx: Tx) => Promise<T>): Promise<T> {
  if (!UUID_RE.test(tenantId)) throw new Error('Invalid tenant id');
  const client = await db().connect();
  try {
    await client.query('BEGIN');
    await client.query(`SELECT set_config('app.current_tenant_id', $1, true)`, [tenantId]);
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (err) {
    await client.query('ROLLBACK').catch(() => undefined);
    throw err;
  } finally {
    client.release();
  }
}

/** Queries against global (non tenant-scoped) tables such as `tenants`. */
export async function globalQuery<R extends pg.QueryResultRow = any>(text: string, params: unknown[] = []) {
  return db().query<R>(text, params);
}

export function isUuid(value: string): boolean {
  return UUID_RE.test(value);
}
