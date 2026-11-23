-- =====================================================================
-- 002_rls.sql — Row-Level Security
-- All application traffic runs as the unprivileged `app_runtime` role.
-- Context is established per transaction with
--   SELECT set_config('app.current_tenant_id', '<uuid>', true)   -- == SET LOCAL
-- which is safe behind PgBouncer in transaction pooling mode.
--
-- Migrations must be run by the schema owner, which needs BYPASSRLS (or be
-- a superuser) so the SECURITY DEFINER functions below can see all rows.
-- =====================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_runtime') THEN
        CREATE ROLE app_runtime WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD 'app_runtime';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO app_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_runtime;

-- Enable + force RLS on every tenant-scoped table and attach the isolation policy.
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['tenant_configs', 'tenant_users', 'borrowers', 'devices', 'loans',
                             'repayments', 'device_lock_events', 'webhook_subscriptions']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%s ON %I', t, t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation_%s ON %I FOR ALL TO app_runtime
               USING (tenant_id = NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)
               WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)',
            t, t);
    END LOOP;
END
$$;

-- Rule 2: Global pre-enrollment IMEI collision verification without exposing
-- cross-tenant data to app_runtime.
CREATE OR REPLACE FUNCTION check_imei_availability(target_imei VARCHAR)
RETURNS TABLE (is_enrolled BOOLEAN, owning_tenant_id UUID)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
        SELECT true AS is_enrolled, d.tenant_id AS owning_tenant_id
        FROM devices d
        WHERE d.imei = target_imei OR d.imei_2 = target_imei
        LIMIT 1;
    IF NOT FOUND THEN
        RETURN QUERY SELECT false, NULL::UUID;
    END IF;
END;
$$;

REVOKE EXECUTE ON FUNCTION check_imei_availability(VARCHAR) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION check_imei_availability(VARCHAR) TO app_runtime;
