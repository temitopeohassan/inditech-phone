import jwt, { type SignOptions } from 'jsonwebtoken';
import { config } from '../config.js';
import { unauthorized } from './errors.js';

export type Role = 'admin' | 'credit_manager' | 'operator' | 'agent';

export interface AdminClaims {
  typ: 'admin';
  sub: string; // tenant_user id
  tenant_id: string;
  role: Role;
}

export interface DeviceClaims {
  typ: 'device';
  sub: string; // device id
  tenant_id: string;
}

export interface ProvisioningClaims {
  typ: 'provisioning';
  tenant_id: string;
  jti: string;
}

type AnyClaims = AdminClaims | DeviceClaims | ProvisioningClaims;

function sign(claims: AnyClaims, secret: string, expiresIn: string): string {
  return jwt.sign(claims, secret, { algorithm: 'HS256', expiresIn } as SignOptions);
}

function verify<T extends AnyClaims>(token: string, secret: string, typ: T['typ']): T {
  try {
    const payload = jwt.verify(token, secret, { algorithms: ['HS256'] }) as T;
    if (payload.typ !== typ) throw new Error('wrong token type');
    if (!payload.tenant_id) throw new Error('missing tenant');
    return payload;
  } catch {
    throw unauthorized('Invalid or Expired Bearer Token');
  }
}

export const tokens = {
  signAdmin: (c: Omit<AdminClaims, 'typ'>) => sign({ typ: 'admin', ...c }, config.jwtSecret, config.adminJwtTtl),
  signDevice: (c: Omit<DeviceClaims, 'typ'>) => sign({ typ: 'device', ...c }, config.jwtSecret, config.deviceJwtTtl),
  signProvisioning: (c: Omit<ProvisioningClaims, 'typ'>, ttl = '7d') =>
    sign({ typ: 'provisioning', ...c }, config.provisioningJwtSecret, ttl),
  verifyAdmin: (t: string) => verify<AdminClaims>(t, config.jwtSecret, 'admin'),
  verifyDevice: (t: string) => verify<DeviceClaims>(t, config.jwtSecret, 'device'),
  verifyProvisioning: (t: string) => verify<ProvisioningClaims>(t, config.provisioningJwtSecret, 'provisioning'),
};
