import { describe, expect, it } from 'vitest';
import { generateOfflineUnlockCode, offlineWindow, verifyOfflineUnlockCode } from '../src/domain/offlineUnlock.js';

// Shared test vector — the Android client (OfflineUnlockCodeTest.kt) asserts the same value.
const SECRET = '000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f';
const DEVICE = 'dev_7f9c3a1b8e2d4f0';

describe('offline unlock codes', () => {
  it('produces stable 8 digit codes (cross-platform vector)', () => {
    const code = generateOfflineUnlockCode(SECRET, DEVICE, 123456);
    expect(code).toMatch(/^\d{8}$/);
    expect(code).toBe(generateOfflineUnlockCode(SECRET, DEVICE, 123456));
    expect(code).toBe('64754689');
  });

  it('accepts current and previous window only', () => {
    const windowSeconds = 21600;
    const now = 1_700_000_000;
    const w = offlineWindow(now, windowSeconds);
    expect(verifyOfflineUnlockCode(SECRET, DEVICE, generateOfflineUnlockCode(SECRET, DEVICE, w), now, windowSeconds)).toBe(true);
    expect(verifyOfflineUnlockCode(SECRET, DEVICE, generateOfflineUnlockCode(SECRET, DEVICE, w - 1), now, windowSeconds)).toBe(true);
    expect(verifyOfflineUnlockCode(SECRET, DEVICE, generateOfflineUnlockCode(SECRET, DEVICE, w - 2), now, windowSeconds)).toBe(false);
    expect(verifyOfflineUnlockCode(SECRET, 'other-device', generateOfflineUnlockCode(SECRET, DEVICE, w), now, windowSeconds)).toBe(false);
  });
});
