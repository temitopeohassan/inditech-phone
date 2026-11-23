import { describe, expect, it } from 'vitest';
import { applyBrandingUpdate, defaultTenantConfig, renderTemplate, tenantConfigSchema } from '../src/domain/tenantConfig.js';

describe('DynamicTenantConfiguration', () => {
  const base = defaultTenantConfig('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'Acme Finance');

  it('default config satisfies the schema', () => {
    expect(() => tenantConfigSchema.parse(base)).not.toThrow();
  });

  it('applies partial branding updates and bumps the version', () => {
    const next = applyBrandingUpdate(base, { identity: { theme: { primary_color: '#FF6600' } } });
    expect(next.version).toBe(2);
    expect(next.identity.theme.primary_color).toBe('#FF6600');
    expect(next.identity.theme.secondary_color).toBe(base.identity.theme.secondary_color);
  });

  it('rejects invalid colors and radius', () => {
    expect(() => applyBrandingUpdate(base, { identity: { theme: { primary_color: 'red' } } })).toThrow();
    expect(() => applyBrandingUpdate(base, { identity: { theme: { button_corner_radius: 64 } } })).toThrow();
  });

  it('renders localized templates', () => {
    expect(renderTemplate('Pay {{amount}} by {{ date }}', { amount: '$45.00', date: 'Oct 15' })).toBe('Pay $45.00 by Oct 15');
  });
});
