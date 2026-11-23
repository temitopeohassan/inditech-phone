/**
 * Seeds a demo tenant through the public API (server must be running):
 *   npm run seed            # API_URL defaults to http://localhost:8080
 */
const API = process.env.API_URL ?? 'http://localhost:8080';
const PLATFORM_KEY = process.env.PLATFORM_ADMIN_KEY ?? 'dev-platform-admin-key';

async function call(method: string, path: string, body?: unknown, headers: Record<string, string> = {}) {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: { 'content-type': 'application/json', ...headers },
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status}: ${JSON.stringify(json)}`);
  return json as any;
}

const slug = process.env.SEED_SLUG ?? 'demo-finance';
const tenant = await call(
  'POST',
  '/api/v1/platform/tenants',
  {
    name: 'Demo Finance',
    slug,
    admin_email: 'admin@demo-finance.test',
    admin_password: 'demo-password-123',
    config: {
      identity: {
        name: 'Demo Finance',
        logo_url: 'https://assets.example.com/demo/logo.png',
        theme: { primary_color: '#0B5FFF', secondary_color: '#00C2A8', background_color: '#0E1525', surface_color: '#1A2338', text_color: '#FFFFFF', button_corner_radius: 14 },
      },
      repayment_settings: {
        payment_modes: [
          { type: 'VIRTUAL_ACCOUNT', enabled: true, instructions: 'Transfer to your dedicated account', bank_name: 'Demo Bank', account_number: '0123456789' },
          { type: 'USSD', enabled: true, instructions: 'Dial and follow the prompts', ussd_code: '*737*50*45#' },
          { type: 'WEB_CHECKOUT', enabled: true, instructions: 'Pay online with card', checkout_url: 'https://pay.example.com/checkout' },
        ],
        whitelisted_domains: ['pay.example.com'],
      },
    },
  },
  { 'x-platform-key': PLATFORM_KEY },
);
const login = await call('POST', '/api/v1/admin/auth/login', { tenant_slug: slug, email: 'admin@demo-finance.test', password: 'demo-password-123' });
const auth = { authorization: `Bearer ${login.access_token}` };
const borrower = await call('POST', '/api/v1/admin/borrowers', { national_id: 'NIN-0001', first_name: 'Ada', last_name: 'Obi', phone_number: '+2348000000001' }, auth);
const loan = await call(
  'POST',
  '/api/v1/admin/loans',
  { borrower_id: borrower.id, imei: '864209041182391', principal_amount: 270, installment_amount: 45, first_due_at: new Date(Date.now() + 7 * 86_400_000).toISOString(), model: 'Galaxy A14', account_ref: 'LND-894120' },
  auth,
);
const prov = await call('POST', `/api/v1/admin/tenants/${tenant.id}/provisioning-tokens`, {}, auth);

console.log(JSON.stringify({ tenant, admin_token: login.access_token, loan: loan.loan, qr_payload: prov.qr_payload }, null, 2));
