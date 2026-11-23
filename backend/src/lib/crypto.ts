import crypto from 'node:crypto';

export function hmacSha256Hex(secret: string, payload: Buffer | string): string {
  return crypto.createHmac('sha256', secret).update(payload).digest('hex');
}

/** Constant-time signature comparison that never throws on length mismatch. */
export function safeEqual(a: string | undefined, b: string): boolean {
  if (!a) return false;
  const ab = Buffer.from(a);
  const bb = Buffer.from(b);
  if (ab.length !== bb.length) return false;
  return crypto.timingSafeEqual(ab, bb);
}

export function sha256Hex(payload: Buffer | string): string {
  return crypto.createHash('sha256').update(payload).digest('hex');
}

export function randomToken(bytes = 32): string {
  return crypto.randomBytes(bytes).toString('hex');
}
