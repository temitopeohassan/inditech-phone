import mqtt, { type MqttClient } from 'mqtt';
import { config } from '../config.js';
import { logger } from '../lib/logger.js';

/**
 * Dual-channel command delivery:
 *   1. High-priority data-only FCM push
 *   2. Persistent MQTT fallback socket (retained message per device so a
 *      reconnecting handset immediately receives its latest desired state)
 */

export interface DeviceCommandMessage {
  commandType: string; // LOCK | SOFT_LOCK | WARN | UNLOCK | RELEASE
  commandId: string;
  targetState: string;
  timestamp: string;
}

export class FcmTokenExpiredError extends Error {}

type FcmSender = (token: string, data: Record<string, string>) => Promise<string>;
let fcmSender: FcmSender | undefined;

async function getFcmSender(): Promise<FcmSender> {
  if (fcmSender) return fcmSender;
  if (!config.firebaseEnabled) {
    fcmSender = async (token, data) => {
      logger.info({ token: token.slice(0, 12), data }, '[fcm:dry-run] push not sent (GOOGLE_APPLICATION_CREDENTIALS unset)');
      return `dry-run-${data.commandId}`;
    };
    return fcmSender;
  }
  const { initializeApp, getApps, applicationDefault } = await import('firebase-admin/app');
  const { getMessaging } = await import('firebase-admin/messaging');
  if (!getApps().length) initializeApp({ credential: applicationDefault() });
  fcmSender = async (token, data) => {
    try {
      return await getMessaging().send({ token, data, android: { priority: 'high', ttl: 0 } });
    } catch (err: any) {
      if (err?.code === 'messaging/registration-token-not-registered') throw new FcmTokenExpiredError(err.message);
      throw err;
    }
  };
  return fcmSender;
}

/** Overridable for tests. */
export function setFcmSender(sender: FcmSender | undefined): void {
  fcmSender = sender;
}

export async function sendFcm(token: string, msg: DeviceCommandMessage): Promise<string> {
  const sender = await getFcmSender();
  return sender(token, { ...msg });
}

let mqttClient: MqttClient | undefined;

function getMqtt(): MqttClient | undefined {
  if (!config.mqttUrl) return undefined;
  if (!mqttClient) {
    mqttClient = mqtt.connect(config.mqttUrl, { clientId: `platform-${process.pid}-${Date.now()}`, reconnectPeriod: 2000 });
    mqttClient.on('error', (err) => logger.warn({ err: err.message }, 'mqtt error'));
  }
  return mqttClient;
}

/** Rule 3 — broker channels are tenant prefixed. */
export function deviceTopic(tenantId: string, deviceId: string): string {
  return `tenants/${tenantId}/devices/${deviceId}/commands`;
}

export async function publishMqtt(tenantId: string, deviceId: string, msg: DeviceCommandMessage): Promise<boolean> {
  const client = getMqtt();
  if (!client || !client.connected) return false;
  await client.publishAsync(deviceTopic(tenantId, deviceId), JSON.stringify(msg), { qos: 1, retain: true });
  return true;
}

export async function closeMessaging(): Promise<void> {
  if (mqttClient) {
    await mqttClient.endAsync();
    mqttClient = undefined;
  }
}
