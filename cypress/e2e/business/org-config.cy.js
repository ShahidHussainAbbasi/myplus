/**
 * common-settings shared library — business-service as the SECOND consumer (after the education pilot).
 *
 * Proves the extraction works: the shared SettingsService/Controller (in common-settings) wire into
 * business-service purely from a dependency + a SettingsStore bean + a catalog provider — no per-service
 * copy of the engine. Drives the shared /settings endpoint directly through the gateway (business has no
 * dashboard Config screen yet — that's the UI follow-on).
 *
 * Catalog served with effective values; owner can override per-org; a non-owner is refused; unknown keys
 * rejected. Restores state so reruns are clean.
 */
const GW = 'http://localhost:8765';
const PW = 'Demo@2025!';
const KEY = 'pos.sale.negativeStockAllowed';   // a BOOL entry from BusinessSettingsCatalog, default false

const login = (email) =>
  cy.request({
    method: 'POST', url: `${GW}/api/auth/login`,
    headers: { 'Content-Type': 'application/json' }, body: { email, password: PW }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `login ${email}: ${JSON.stringify(r.body)}`).to.eq(200);
    return r.body.data.accessToken;
  });

const listSettings = (token) =>
  cy.request({ url: `${GW}/api/business/settings`, headers: { Authorization: `Bearer ${token}` },
    failOnStatusCode: false });

const saveSetting = (token, key, value) =>
  cy.request({ method: 'POST', url: `${GW}/api/business/settings?key=${key}&value=${value}`,
    headers: { Authorization: `Bearer ${token}` }, failOnStatusCode: false });

const entry = (body, key) => ((body && body.data) || []).find((e) => e.key === key);

describe('common-settings: business-service consumes the shared engine', () => {
  let owner, cashier;

  before(() => {
    login('owner.business@myplus.com').then((t) => { owner = t; });
    login('cashier.a@myplus.com').then((t) => { cashier = t; });   // ROLE_BUSINESS_USER — not owner/admin
  });

  it('serves the business catalog with effective values', () => {
    listSettings(owner).then((r) => {
      expect(r.status, JSON.stringify(r.body)).to.eq(200);
      const e = entry(r.body, KEY);
      expect(e, 'business catalog entry present (shared engine wired)').to.exist;
      expect(e.type).to.eq('BOOL');
      expect(String(e.value), 'defaults false').to.eq('false');
      expect(e.group).to.eq('Sales');
    });
  });

  it('an owner override persists per-org (override else default)', () => {
    saveSetting(owner, KEY, 'true').then((r) =>
      expect(r.body && r.body.success, JSON.stringify(r.body)).to.eq(true));
    listSettings(owner).then((r) => {
      const e = entry(r.body, KEY);
      expect(String(e.value), 'override reflected').to.eq('true');
      expect(e.isDefault, 'now an org override, not the default').to.eq(false);
    });
    // restore
    saveSetting(owner, KEY, 'false').then((r) => expect(r.body.success).to.eq(true));
  });

  it('a non-owner (cashier) cannot change settings', () => {
    saveSetting(cashier, KEY, 'true').then((r) => {
      const denied = r.status === 403 || (r.body && r.body.success === false);
      expect(denied, `cashier was allowed to write config: ${r.status} ${JSON.stringify(r.body)}`).to.eq(true);
    });
    // confirm unchanged from the owner's side
    listSettings(owner).then((r) => expect(String(entry(r.body, KEY).value)).to.eq('false'));
  });

  it('rejects an unknown setting key', () => {
    saveSetting(owner, 'pos.not.a.real.key', 'true').then((r) => {
      const rejected = r.status >= 400 || (r.body && r.body.success === false);
      expect(rejected, `unknown key accepted: ${JSON.stringify(r.body)}`).to.eq(true);
    });
  });
});
