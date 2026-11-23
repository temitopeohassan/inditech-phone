import 'node:process';

function env(name: string, fallback?: string): string {
  const value = process.env[name] ?? fallback;
  if (value === undefined) throw new Error(`Missing required environment variable ${name}`);
  return value;
}

export const config = {
  port: Number(env('PORT', '8080')),
  nodeEnv: env('NODE_ENV', 'development'),
  publicApiUrl: env('PUBLIC_API_URL', 'http://localhost:8080'),
  platformApiHost: env('PLATFORM_API_HOST', 'localhost:8080'),
  databaseUrl: env('DATABASE_URL', 'postgres://app_runtime:app_runtime@localhost:5432/device_platform'),
  databaseAdminUrl: env('DATABASE_ADMIN_URL', 'postgres://postgres:postgres@localhost:5432/device_platform'),
  redisUrl: env('REDIS_URL', 'redis://localhost:6379'),
  mqttUrl: process.env.MQTT_URL ?? '',
  jwtSecret: env('JWT_SECRET', 'dev-platform-jwt-secret'),
  provisioningJwtSecret: env('PROVISIONING_JWT_SECRET', 'dev-provisioning-secret'),
  deviceJwtTtl: env('DEVICE_JWT_TTL', '30d'),
  adminJwtTtl: env('ADMIN_JWT_TTL', '12h'),
  platformAdminKey: env('PLATFORM_ADMIN_KEY', 'dev-platform-admin-key'),
  firebaseEnabled: Boolean(process.env.GOOGLE_APPLICATION_CREDENTIALS),
  dpc: {
    packageName: env('DPC_PACKAGE_NAME', 'com.inditech.dpc'),
    adminComponent: env('DPC_ADMIN_COMPONENT', 'com.inditech.dpc/.admin.EnterpriseAdminReceiver'),
    signatureChecksum: env('DPC_SIGNATURE_CHECKSUM', ''),
    downloadUrl: env('DPC_DOWNLOAD_URL', 'https://downloads.example.com/dpc/app-release.apk'),
  },
  cron: {
    overdueEvaluator: env('OVERDUE_EVALUATOR_CRON', '*/10 * * * *'),
    heartbeatReconciliation: env('HEARTBEAT_RECONCILIATION_CRON', '*/15 * * * *'),
  },
  /** Interval the device is told to check in at (seconds). */
  heartbeatIntervalSeconds: 15 * 60,
  /** Offline unlock code window (seconds). */
  offlineUnlockWindowSeconds: 6 * 60 * 60,
};
