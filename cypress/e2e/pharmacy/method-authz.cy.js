/**
 * Pharmacy method-level authorization (@PreAuthorize) — the gate for the pharma-service privilege slice.
 *
 * The gap: pharma-service enforced ONLY .anyRequest().authenticated(), with no per-endpoint privilege check —
 * so any authenticated user could set or CLEAR a medicine's "controlled substance" flag (silently dropping every
 * later dispense off the regulatory register), rewrite drug-interaction warnings, and read the whole
 * controlled-substance register with its patient names. The privileges already travel in the JWT; nothing was
 * checking them. Same convention as the business/education gates in cypress/e2e/security/method-authz.cy.js.
 *
 * Two accounts with genuinely different privilege sets:
 *   cashier.a@myplus.com      = ROLE_BUSINESS_USER (WRITE, but no ADMIN_PRIVILEGE) -- must be FORBIDDEN
 *   owner.business@myplus.com = ROLE_OWNER (super set, has ADMIN_PRIVILEGE)        -- must get past the gate
 *
 * Requests go straight to the gateway (:8765) with a Bearer token — the point is server-side enforcement,
 * independent of the monolith UI (which merely hides these screens for non-PHARMA users).
 *
 * NOTE: the WRITE_PRIVILEGE gates (prescription intake + dispense) are proven only in the positive direction —
 * there is no seeded guest-role account to prove the negative with. If a GUEST_ROLE fixture is ever seeded,
 * add: intake/dispense must be FORBIDDEN for it.
 *
 * Run headed:  npx cypress run --headed --spec cypress/e2e/pharmacy/method-authz.cy.js
 */
const GW = 'http://localhost:8765';
const PW = 'Demo@2025!';

const login = (email) =>
  cy.request({
    method: 'POST', url: `${GW}/api/auth/login`,
    headers: { 'Content-Type': 'application/json' }, body: { email, password: PW },
    failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `login ${email}: ${JSON.stringify(r.body)}`).to.eq(200);
    return r.body.data.accessToken;
  });

const pharmaPost = (token, path, body) =>
  cy.request({
    method: 'POST', url: `${GW}/api/pharma${path}`,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body || {}, failOnStatusCode: false,
  });

const pharmaGet = (token, path) =>
  cy.request({
    method: 'GET', url: `${GW}/api/pharma${path}`,
    headers: { Authorization: `Bearer ${token}` }, failOnStatusCode: false,
  });

// Denied = 403, OR a 2xx whose envelope is not a success. @PreAuthorize blocks the method before it runs
// either way; what must never happen is a real SUCCESS.
const expectForbidden = (r, label) => {
  if (r.status === 403) return;
  expect(r.status, `${label}: unexpected ${r.status} ${JSON.stringify(r.body)}`).to.be.lessThan(500);
  const b = r.body || {};
  const succeeded = b.status === 'SUCCESS' || b.success === true || b === true;
  expect(succeeded, `${label}: a USER was ALLOWED a privileged pharmacy op — @PreAuthorize not enforced: ${JSON.stringify(b)}`)
    .to.eq(false);
};

describe('Security: pharmacy clinical/regulatory ops are @PreAuthorize-gated (pharma-service)', () => {
  let userToken;    // cashier — WRITE but no ADMIN_PRIVILEGE
  let ownerToken;   // owner — full super set

  beforeEach(() => {
    // testIsolation clears session between tests — re-login for the authed cy.requests.
    login('cashier.a@myplus.com').then((t) => { userToken = t; });
    login('owner.business@myplus.com').then((t) => { ownerToken = t; });
  });

  it('a plain USER is FORBIDDEN: saveClinical — set/clear the controlled-substance flag (ADMIN_PRIVILEGE)', () => {
    pharmaPost(userToken, '/clinical',
      { productId: 999999, medicineName: 'CY_NOPE', rxRequired: true, controlledSubstance: false })
      .then((r) => expectForbidden(r, 'upsertClinical'));
  });

  it('a plain USER is FORBIDDEN: addInteraction — rewrite a drug-interaction warning (ADMIN_PRIVILEGE)', () => {
    pharmaPost(userToken, '/interactions',
      { productId1: 999999, productId2: 999998, severity: 'MILD', description: 'CY_NOPE' })
      .then((r) => expectForbidden(r, 'addInteraction'));
  });

  it('a plain USER is FORBIDDEN: controlled-register — patient names on controlled dispenses (ADMIN_PRIVILEGE)', () => {
    pharmaGet(userToken, '/controlled-register').then((r) => expectForbidden(r, 'controlledRegister'));
  });

  // Positive controls: the gate must not lock out the roles that legitimately need these ops.
  it('the owner gets past the ADMIN gate (not 403) — controlled-register', () => {
    pharmaGet(ownerToken, '/controlled-register')
      .then((r) => expect(r.status, JSON.stringify(r.body)).to.not.eq(403));
  });

  // A cashier HAS WRITE_PRIVILEGE, so dispensing is still allowed through. Non-existent Rx id → the op is a
  // clean no-op (NOT_FOUND); the point is only that it gets PAST @PreAuthorize.
  it('a cashier gets past the WRITE gate (not 403) — dispense', () => {
    pharmaPost(userToken, '/prescriptions/999999/dispense', { invoiceNo: 'CY-NOPE', items: [] })
      .then((r) => expect(r.status, JSON.stringify(r.body)).to.not.eq(403));
  });
});
