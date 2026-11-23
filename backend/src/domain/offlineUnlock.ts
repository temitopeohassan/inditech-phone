import crypto from 'node:crypto';

/**
 * Time-based offline unlock codes (HOTP-style, RFC 4226 dynamic truncation over
 * HMAC-SHA256). The device holds the same per-device secret (delivered at
 * enrollment, stored in EncryptedSharedPreferences) and validates codes using its
 * tamper-resistant monotonic clock — no connectivity required.
 *
 * Must stay byte-for-byte identical to mobile/app/.../domain/OfflineUnlockCode.kt
 */
export const OFFLINE_CODE_DIGITS = 8;

export function offlineWindow(epochSeconds: number, windowSeconds: number): number {
  return Math.floor(epochSeconds / windowSeconds);
}

export function generateOfflineUnlockCode(secretHex: string, deviceId: string, window: number): string {
  const message = Buffer.from(`UNLOCK:${deviceId}:${window}`, 'utf8');
  const mac = crypto.createHmac('sha256', Buffer.from(secretHex, 'hex')).update(message).digest();
  const offset = mac[mac.length - 1]! & 0x0f;
  const binary =
    ((mac[offset]! & 0x7f) << 24) | ((mac[offset + 1]! & 0xff) << 16) | ((mac[offset + 2]! & 0xff) << 8) | (mac[offset + 3]! & 0xff);
  return String(binary % 10 ** OFFLINE_CODE_DIGITS).padStart(OFFLINE_CODE_DIGITS, '0');
}

/** Accepts the current and the previous window to tolerate small clock drift. */
export function verifyOfflineUnlockCode(
  secretHex: string,
  deviceId: string,
  code: string,
  epochSeconds: number,
  windowSeconds: number,
): boolean {
  const w = offlineWindow(epochSeconds, windowSeconds);
  return [w, w - 1].some((candidate) => generateOfflineUnlockCode(secretHex, deviceId, candidate) === code);
}
