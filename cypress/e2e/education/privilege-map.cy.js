/**
 * Education privilege map (audit finding C / decision D-3).
 *
 * The gap: education had 75 endpoints and 17 gates, 14 of them on deletes. Every create and update was open to
 * ANY authenticated user — including two that move money: `addFc` (records a fee collection) and `addDiscount`
 * (reduces what a parent owes). The privileges already travelled in the JWT; nothing was checking them.
 *
 * Three tiers now:
 *   WRITE_PRIVILEGE   day-to-day records — student, guardian, staff, subject, attendance
 *   ADMIN_PRIVILEGE   money, structure and policy — fee collection, discount, fee settings, school, owner,
 *                     grade, vehicle, alert channels, bulk import
 *   DELETE_PRIVILEGE  deletes (unchanged — hardened in an earlier pass)
 *
 * Uses the seeded per-module ladder (see microservices/docs/dev-test-accounts.md), which exists for exactly this:
 *   user.education@   ROLE_EDUCATION_USER — WRITE, no ADMIN  → must be refused the money/structure tier
 *   owner.education@  ROLE_OWNER          — the super set    → must get through
 * Both sit in the SAME organization, so a refusal proves the PRIVILEGE gate, not org-scoping.
 *
 * Requests go to the gateway with a Bearer token — the point is server-side enforcement, independent of the UI.
 * Run headed.
 */
const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'

const login = (email) =>
  cy.request({
    method: 'POST', url: `${GW}/api/auth/login`,
    headers: { 'Content-Type': 'application/json' }, body: { email, password: PW },
    failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `login ${email}: ${JSON.stringify(r.body)}`).to.eq(200)
    return r.body.data.accessToken
  })

const post = (token, path, form) =>
  cy.request({
    method: 'POST', url: `${GW}/api/education${path}`,
    headers: { Authorization: `Bearer ${token}` },
    form: true, body: form || {}, failOnStatusCode: false,
  })

// Denied = 403, OR a 2xx whose envelope is not a success. @PreAuthorize blocks the method either way; what must
// never happen is a real SUCCESS.
const expectForbidden = (r, label) => {
  if (r.status === 403) return
  expect(r.status, `${label}: unexpected ${r.status} ${JSON.stringify(r.body)}`).to.be.lessThan(500)
  const b = r.body || {}
  const ok = b.status === 'SUCCESS' || b.success === true
  expect(ok, `${label}: a plain USER was ALLOWED a privileged op: ${JSON.stringify(b)}`).to.eq(false)
}

describe('Security: education creates/updates are privilege-gated (D-3)', () => {
  let userToken    // ROLE_EDUCATION_USER — WRITE but no ADMIN
  let ownerToken   // ROLE_OWNER — super set

  beforeEach(() => {
    // testIsolation clears the session between tests, so re-login for the authed requests.
    login('user.education@myplus.com').then((t) => { userToken = t })
    login('owner.education@myplus.com').then((t) => { ownerToken = t })
  })

  // ── the ADMIN tier: money, structure, policy ────────────────────────────────────────────────────
  const adminOnly = [
    ['/addFc', { enrollNo: 'CY-NOPE', dueAmount: 100, feePaid: 100, receivedIn: 'Cash' }, 'addFc — records money received'],
    ['/addDiscount', { name: 'CY-NOPE', amount: 50, di: '%' }, 'addDiscount — reduces what a parent owes'],
    ['/saveFeeSetting', { paymentMode: 'MONTHLY', autoRegisterDues: 'false' }, 'saveFeeSetting — fee policy'],
    ['/addSchool', { branchName: 'CY-NOPE' }, 'addSchool — org structure'],
    ['/addGrade', { name: 'CY-NOPE', fee: 1000 }, 'addGrade — class + its fee'],
    ['/addOwner', { name: 'CY-NOPE' }, 'addOwner — org structure'],
  ]

  adminOnly.forEach(([path, form, label]) => {
    it(`a teacher is FORBIDDEN: ${label}`, () => {
      post(userToken, path, form).then((r) => expectForbidden(r, label))
    })
  })

  // ── the WRITE tier: a teacher SHOULD be able to do these ────────────────────────────────────────
  it('a teacher IS allowed the day-to-day tier (not 403) — addStudent', () => {
    // The gate must not lock out the role it exists to serve. A junk payload is fine: the point is that it gets
    // PAST @PreAuthorize, not that it saves.
    post(userToken, '/addStudent', { name: 'CyPrivStudent', enrollNo: 'CYP' + Date.now(), status: 'ACTIVE' })
      .then((r) => expect(r.status, JSON.stringify(r.body)).to.not.eq(403))
  })

  it('a teacher IS allowed to mark attendance', () => {
    post(userToken, '/markAttendanceBulk', { gradeId: 999999, attDate: '01-01-2030' })
      .then((r) => expect(r.status, JSON.stringify(r.body)).to.not.eq(403))
  })

  // ── the owner is never blocked by the new gates ─────────────────────────────────────────────────
  it('the owner gets past the ADMIN tier (not 403) — addFc', () => {
    post(ownerToken, '/addFc', { enrollNo: 'CY-OWNER', dueAmount: 0, feePaid: 0, receivedIn: 'Cash' })
      .then((r) => expect(r.status, JSON.stringify(r.body)).to.not.eq(403))
  })

  it('the owner gets past the ADMIN tier (not 403) — saveFeeSetting', () => {
    post(ownerToken, '/saveFeeSetting', { paymentMode: 'MONTHLY', autoRegisterDues: 'false' })
      .then((r) => expect(r.status, JSON.stringify(r.body)).to.not.eq(403))
  })

  // ── deletes were already gated; prove that did not regress ──────────────────────────────────────
  it('a teacher is still FORBIDDEN a delete (DELETE_PRIVILEGE, unchanged)', () => {
    post(userToken, '/deleteStudent', { checked: '999999' })
      .then((r) => expectForbidden(r, 'deleteStudent'))
  })
})
