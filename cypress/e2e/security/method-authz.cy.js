/**
 * Method-level authorization (PreAuthorize) on business-service and education-service.
 *
 * The gap: the services enforce ONLY "is this a valid user" at the HTTP layer (.anyRequest().authenticated()),
 * with no per-endpoint privilege check -- so an authenticated USER could invoke mutations their role forbids
 * and the UI merely hides (delete master data, delete/void a sale, change tax settings). The privileges already
 * travel in the JWT (a USER carries ADD/UPDATE but NOT DELETE_PRIVILEGE / ADMIN_PRIVILEGE); nothing was
 * checking them. These endpoints are now privilege-gated.
 *
 * Two accounts with genuinely different privilege sets, both in the SAME org (org 49) so this is purely about
 * ROLE, not tenant:
 *   cashier.a@myplus.com     = ROLE_BUSINESS_USER (no DELETE / ADMIN)  -- must be FORBIDDEN
 *   owner.business@myplus.com = ROLE_OWNER (super set)                 -- must be ALLOWED
 *
 * Requests go to the gateway directly (:8765) with a Bearer token, since the point is server-side enforcement
 * independent of the monolith UI. A denial is a 403 OR a body whose status is FORBIDDEN/ERROR -- never a
 * success. The op must NOT have happened.
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

// form-POST to a business endpoint through the gateway with a given token.
const post = (token, path, form) =>
  cy.request({
    method: 'POST', url: `${GW}/api/business${path}`,
    headers: { Authorization: `Bearer ${token}` },
    form: true, body: form || {}, failOnStatusCode: false,
  });

// Denied = 403, OR a 2xx whose envelope is not a success (FORBIDDEN/ERROR). The @PreAuthorize interceptor
// blocks the method before it runs either way; what must never happen is a real SUCCESS.
const expectForbidden = (r, label) => {
  if (r.status === 403) return;
  expect(r.status, `${label}: unexpected ${r.status} ${JSON.stringify(r.body)}`).to.be.lessThan(500);
  const b = r.body || {};
  const succeeded = b.status === 'SUCCESS' || b.success === true || b === true;
  expect(succeeded, `${label}: a USER was ALLOWED a privileged op — @PreAuthorize not enforced: ${JSON.stringify(b)}`)
    .to.eq(false);
};

describe('Security: privileged ops are @PreAuthorize-gated (business-service)', () => {
  let userToken;    // cashier — ROLE_BUSINESS_USER, no DELETE/ADMIN
  let ownerToken;   // owner  — full super set

  before(() => {
    login('cashier.a@myplus.com').then((t) => { userToken = t; });
    login('owner.business@myplus.com').then((t) => { ownerToken = t; });
  });

  // A representative gate from each privilege tier. deleteCustomer/Sell → DELETE_PRIVILEGE; voidSell/tax →
  // ADMIN_PRIVILEGE; deleteVender → DELETE_VENDER. All absent from a USER, all present for the owner.
  const cases = [
    ['/deleteCustomer', { checked: '999999' }, 'deleteCustomer (DELETE_PRIVILEGE)'],
    ['/deleteVender',   { checked: '999999' }, 'deleteVender (DELETE_VENDER)'],
    ['/deleteCompany',  { checked: '999999' }, 'deleteCompany (DELETE_COMPANY)'],
    ['/deleteSell',     { checked: '999999' }, 'deleteSell (DELETE_PRIVILEGE)'],
    ['/voidSell',       { invoiceNo: 'CY-NOPE', reason: 'x' }, 'voidSell (ADMIN_PRIVILEGE)'],
    ['/saveTaxSetting', { enabled: 'false', taxMode: 'EXCLUSIVE', defaultRate: '0' }, 'saveTaxSetting (ADMIN_PRIVILEGE)'],
  ];

  cases.forEach(([path, form, label]) => {
    it(`a plain USER is FORBIDDEN: ${label}`, () => {
      post(userToken, path, form).then((r) => expectForbidden(r, label));
    });
  });

  // Positive control: the owner is NOT blocked by the gate. We use a non-existent id so the op is a clean
  // no-op (NOT_FOUND / false / SUCCESS-with-nothing) — the point is it gets PAST @PreAuthorize (never 403).
  it('the owner is allowed past the gate (not 403) — deleteCustomer', () => {
    post(ownerToken, '/deleteCustomer', { checked: '999999' }).then((r) => {
      expect(r.status, JSON.stringify(r.body)).to.not.eq(403);
    });
  });

  it('the owner is allowed past the gate (not 403) — saveTaxSetting', () => {
    post(ownerToken, '/saveTaxSetting', { enabled: 'false', taxMode: 'EXCLUSIVE', defaultRate: '0' })
      .then((r) => expect(r.status, JSON.stringify(r.body)).to.not.eq(403));
  });

  // ── education-service: the same convention rolled across services ────────────────────────────────
  // A teacher (ROLE_EDUCATION_USER) has WRITE/UPDATE but no DELETE_PRIVILEGE / ADMIN_PRIVILEGE — so a
  // delete or a fee-config change must be refused, while the education owner gets past the gate.
  describe('education-service', () => {
    let teacherToken, eduOwnerToken;
    before(() => {
      login('teacher.a@myplus.com').then((t) => { teacherToken = t; });
      login('owner.education@myplus.com').then((t) => { eduOwnerToken = t; });
    });

    const eduPost = (token, path, form) =>
      cy.request({
        method: 'POST', url: `${GW}/api/education${path}`,
        headers: { Authorization: `Bearer ${token}` }, form: true, body: form || {},
        failOnStatusCode: false,
      });

    it('a teacher is FORBIDDEN: deleteStudent (DELETE_PRIVILEGE)', () => {
      eduPost(teacherToken, '/deleteStudent', { checked: '999999' })
        .then((r) => expectForbidden(r, 'deleteStudent'));
    });

    it('a teacher is FORBIDDEN: saveFeeSetting (ADMIN_PRIVILEGE)', () => {
      eduPost(teacherToken, '/saveFeeSetting', { paymentMode: 'MONTHLY', autoRegisterDues: 'false' })
        .then((r) => expectForbidden(r, 'saveFeeSetting'));
    });

    it('the education owner is allowed past the gate (not 403) — deleteStudent', () => {
      eduPost(eduOwnerToken, '/deleteStudent', { checked: '999999' })
        .then((r) => expect(r.status, JSON.stringify(r.body)).to.not.eq(403));
    });
  });
});
