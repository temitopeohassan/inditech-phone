import { z } from 'zod';

const hex = z.string().regex(/^#[0-9A-Fa-f]{6}$/, 'must be a #RRGGBB hex color');

export const paymentModeSchema = z.object({
  type: z.enum(['VIRTUAL_ACCOUNT', 'USSD', 'WEB_CHECKOUT']),
  enabled: z.boolean(),
  instructions: z.string(),
  ussd_code: z.string().optional(),
  checkout_url: z.string().url().optional(),
  bank_name: z.string().optional(),
  account_number: z.string().optional(),
});

/** DynamicTenantConfiguration — see docs/api.md (JSON Schema draft 2020-12). */
export const tenantConfigSchema = z.object({
  tenant_id: z.string(),
  version: z.number().int(),
  identity: z.object({
    name: z.string().min(1),
    logo_url: z.string().url(),
    wallpaper_url: z.string().url().optional(),
    theme: z.object({
      primary_color: hex,
      secondary_color: hex,
      background_color: hex,
      surface_color: hex,
      text_color: hex,
      button_corner_radius: z.number().min(0).max(32),
    }),
  }),
  support: z.object({
    phone_number: z.string(),
    whatsapp_number: z.string(),
    email: z.string().email(),
    allow_emergency_calls: z.boolean(),
    allow_direct_support_call: z.boolean(),
    emergency_numbers: z.array(z.string()).optional(),
    support_url: z.string().url().optional(),
  }),
  repayment_settings: z.object({
    payment_modes: z.array(paymentModeSchema),
    whitelisted_domains: z.array(z.string()),
  }),
  enforcement_policies: z.object({
    grace_period_hours: z.number().int().min(0),
    warning_overlay_hours_before_lock: z.number().int().min(0),
    warning_message: z.string(),
    lock_message: z.string(),
    whitelisted_packages_when_locked: z.array(z.string()),
    offline_lock_fallback: z.object({
      max_offline_days: z.number().int(),
      action_if_exceeded: z.enum(['HARD_LOCK', 'WARN', 'NONE']),
    }),
    // Platform extensions (optional, defaulted)
    soft_lock_hours: z.number().int().min(0).optional(),
    disable_usb_file_transfer: z.boolean().optional(),
    block_outgoing_calls_when_soft_locked: z.boolean().optional(),
  }),
  localization: z
    .object({
      default_locale: z.string(),
      templates: z.record(z.string(), z.record(z.string(), z.string())),
    })
    .optional(),
});

export type TenantConfig = z.infer<typeof tenantConfigSchema>;

/** Partial update accepted by PUT /api/v1/admin/tenants/{tenant_id}/branding */
export const brandingUpdateSchema = z.object({
  identity: tenantConfigSchema.shape.identity.partial().extend({
    theme: tenantConfigSchema.shape.identity.shape.theme.partial().optional(),
  }).optional(),
  support: tenantConfigSchema.shape.support.partial().optional(),
  repayment_settings: tenantConfigSchema.shape.repayment_settings.partial().optional(),
  enforcement_policies: tenantConfigSchema.shape.enforcement_policies.partial().optional(),
  localization: tenantConfigSchema.shape.localization,
});
export type BrandingUpdate = z.infer<typeof brandingUpdateSchema>;

export const DEFAULT_SOFT_LOCK_HOURS = 48;

export function defaultTenantConfig(tenantId: string, name: string): TenantConfig {
  return {
    tenant_id: tenantId,
    version: 1,
    identity: {
      name,
      logo_url: 'https://assets.example.com/default/logo.png',
      theme: {
        primary_color: '#0B5FFF',
        secondary_color: '#00C2A8',
        background_color: '#0E1525',
        surface_color: '#1A2338',
        text_color: '#FFFFFF',
        button_corner_radius: 12,
      },
    },
    support: {
      phone_number: '+2340000000000',
      whatsapp_number: '+2340000000000',
      email: 'support@example.com',
      allow_emergency_calls: true,
      allow_direct_support_call: true,
      emergency_numbers: ['112', '911'],
    },
    repayment_settings: {
      payment_modes: [
        { type: 'USSD', enabled: true, instructions: 'Dial the code below and follow the prompts.', ussd_code: '*737*000#' },
      ],
      whitelisted_domains: [],
    },
    enforcement_policies: {
      grace_period_hours: 72,
      warning_overlay_hours_before_lock: 24,
      warning_message: 'Your payment is due soon. Pay now to avoid restrictions.',
      lock_message: 'This device is locked because your payment is overdue.',
      whitelisted_packages_when_locked: ['com.android.dialer', 'com.google.android.dialer'],
      offline_lock_fallback: { max_offline_days: 3, action_if_exceeded: 'HARD_LOCK' },
      soft_lock_hours: DEFAULT_SOFT_LOCK_HOURS,
    },
  };
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/** Deep-merges a partial update (arrays are replaced, not concatenated). */
export function deepMerge<T>(base: T, patch: unknown): T {
  if (!isPlainObject(base) || !isPlainObject(patch)) return (patch === undefined ? base : patch) as T;
  const out: Record<string, unknown> = { ...base };
  for (const [k, v] of Object.entries(patch)) {
    if (v === undefined) continue;
    out[k] = isPlainObject(v) && isPlainObject(out[k]) ? deepMerge(out[k], v) : v;
  }
  return out as T;
}

/** Applies a branding update, bumps the version and re-validates the full document. */
export function applyBrandingUpdate(current: TenantConfig, update: BrandingUpdate): TenantConfig {
  const merged = deepMerge(current, update);
  merged.version = current.version + 1;
  return tenantConfigSchema.parse(merged);
}

/** Substitutes {{variables}} in a localized notification template. */
export function renderTemplate(template: string, vars: Record<string, string | number>): string {
  return template.replace(/\{\{\s*(\w+)\s*\}\}/g, (_, key: string) => (key in vars ? String(vars[key]) : `{{${key}}}`));
}
