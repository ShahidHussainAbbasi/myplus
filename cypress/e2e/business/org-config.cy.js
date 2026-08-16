/**
 * common-settings shared library — business-service as the SECOND consumer (after the education pilot).
 *
 * Proves the extraction works: the shared SettingsService/Controller (in common-settings) wire into
 * business-service purely from a dependency + a SettingsStore bean + a catalog provider — no per-service
 * copy of the engine. Drives the shared /settings endpoint directly through the gateway (the dashboard
 * Config screen exercises the same endpoint via the monolith proxy; see receipt-tax-breakdown.cy.js).
 *
 * Catalog served with effective values; owner can override per-org; a non-owner is refused; unknown keys
 * rejected. Restores state so reruns are clean.
 */
const GW = 'http://localhost:8765';
const PW = 'Demo@2025!';
const KEY = 'pos.receipt.showTaxBreakdown';   // a BOOL entry from BusinessSettingsCatalog, default true

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

  /**
   * Restore the default UNCONDITIONALLY.
   *
   * The override→restore pair below lives inside a test body, so a case that fails between them leaves
   * this org's `pos.receipt.showTaxBreakdown` stuck at false. That is a PER-TENANT SERVER setting: it
   * outlives the spec, `testIsolation` does not touch it, and `receipt-tax-breakdown.cy.js` asserts the
   * very same key — so one failure here silently reddens a different spec. The header's claim that this
   * file "restores state so reruns are clean" held only on the happy path.
   *
   * A fresh token, because `before`'s is ~15 minutes old by now and the access-token lifetime is exactly
   * that; a silently-expired bearer would 401 and leave the setting wrong.
   */
  after(() => {
    login('owner.business@myplus.com').then((t) => {
      saveSetting(t, KEY, 'true');
      listSettings(t).then((r) => {
        expect(String(entry(r.body, KEY).value), 'default restored for the rest of the suite').to.eq('true');
      });
    });
  });

  it('serves the business catalog with effective values', () => {
    listSettings(owner).then((r) => {
      expect(r.status, JSON.stringify(r.body)).to.eq(200);
      const e = entry(r.body, KEY);
      expect(e, 'business catalog entry present (shared engine wired)').to.exist;
      expect(e.type).to.eq('BOOL');
      expect(String(e.value), 'defaults true').to.eq('true');
      expect(e.group).to.eq('Receipts');
    });
  });

  it('an owner override persists per-org (override else default)', () => {
    saveSetting(owner, KEY, 'false').then((r) =>
      expect(r.body && r.body.success, JSON.stringify(r.body)).to.eq(true));
    listSettings(owner).then((r) => {
      const e = entry(r.body, KEY);
      expect(String(e.value), 'override reflected').to.eq('false');
      expect(e.isDefault, 'now an org override, not the default').to.eq(false);
    });
    // restore to the functional default (on)
    saveSetting(owner, KEY, 'true').then((r) => expect(r.body.success).to.eq(true));
  });

  it('a non-owner (cashier) cannot change settings', () => {
    saveSetting(cashier, KEY, 'false').then((r) => {
      const denied = r.status === 403 || (r.body && r.body.success === false);
      expect(denied, `cashier was allowed to write config: ${r.status} ${JSON.stringify(r.body)}`).to.eq(true);
    });
    // confirm unchanged from the owner's side (restored to on above)
    listSettings(owner).then((r) => expect(String(entry(r.body, KEY).value)).to.eq('true'));
  });

  it('rejects an unknown setting key', () => {
    saveSetting(owner, 'pos.not.a.real.key', 'true').then((r) => {
      const rejected = r.status >= 400 || (r.body && r.body.success === false);
      expect(rejected, `unknown key accepted: ${JSON.stringify(r.body)}`).to.eq(true);
    });
  });
});
