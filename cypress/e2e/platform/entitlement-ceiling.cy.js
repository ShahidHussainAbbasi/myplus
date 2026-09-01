/**
 * E1 — the ENTITLEMENT CEILING.
 *
 * Design: microservices/docs/slices/e1-entitlement-ceiling.md
 * Programme: microservices/docs/saas-control-plane-review.md (E0..E6)
 *
 * ── The defect this gates ───────────────────────────────────────────────────────────────────────
 * `SettingsService.set` validated that a key was in the catalog and nothing else, and `org.cap.*` keys ARE in
 * the catalog. So any tenant owner could POST `org.cap.installments=true` and hold a paid capability for
 * nothing. Org scoping was never breached — this is a LICENSING hole, not a tenancy one, and it is the one
 * layer of the four-layer control model that did not exist.
 *
 * ── The rule, and the correction that took two rounds to get right ──────────────────────────────
 *   effective = NOT REVOKED (the platform withdrew it) AND ENABLED (owner switched it on)
 *               AND PERMITTED (privilege) AND IN SCOPE (branch)
 *
 * TWO questions, not one, because they have opposite safe answers:
 *   grantable  may the owner switch this ON?   plan ∪ rows   → the WRITE guard; a person is told why
 *   revoked    has this been WITHDRAWN?        rows ONLY     → the read path; only positive evidence subtracts
 *
 * The first design asked "is it in the plan?" on BOTH, and every legacy tenant carries plan = FREE from
 * @Builder.Default — a value nothing had ever read for capability. So the deploy measured tenants against a
 * plan nobody had sold them, and capability-shapes.cy.js went red with every capability off. Silence in a
 * licensing table is a data gap, not a customer who has not paid.
 *
 * ── ⚠ Assert the ENVELOPE, never the HTTP status ────────────────────────────────────────────────
 * A refusal arrives as **200 with `success:false`** — `ProxyErrors`' documented rule, "a refusal is an ANSWER,
 * not a failure". `expect(status).to.eq(200)` PASSES on a refusal; `expect(status).to.not.eq(200)` FAILS on a
 * working call. This has now caught this codebase four times, so it is stated at the top of the file.
 *
 * ── ⚠ An after-state assertion is only evidence when the before-state is the opposite ───────────
 * Case 4 switches installments ON and confirms it, and only then revokes and attempts the write. Checking
 * "still off" against something that was already off reads the same whether the guard fired or not.
 *
 * ── Accounts ────────────────────────────────────────────────────────────────────────────────────
 *   owner.business@   the tenant whose Configuration screen this is (owner-gated)
 *   user.business@    same org, no ADMIN_PRIVILEGE — the privilege ladder rung
 *   admin@myplus.com  the PLATFORM operator (ROLE_ADMIN, not ADMIN_PRIVILEGE) — see §8 of the design
 */

const GW = 'http://localhost:8765'
const OWNER = 'owner.business@myplus.com'
const DEMO_PW = 'Demo@2025!'
const OPERATOR = 'admin@myplus.com'
const OPERATOR_PW = Cypress.env('adminPassword') || 'Admin@2025!'

const CAP = 'installments'          // the capability under test — a genuinely chargeable one
const CAP_KEY = 'org.cap.installments'

/** Log in at the gateway and yield the access token. Fails loudly rather than yielding undefined. */
const gwLogin = (email, password) =>
  cy
    .request({
      method: 'POST',
      url: `${GW}/api/auth/login`,
      headers: { 'Content-Type': 'application/json' },
      body: { email, password },
      failOnStatusCode: false,
    })
    .then((r) => {
      expect(r.status, `gateway login ${email}: ${JSON.stringify(r.body)}`).to.eq(200)
      const token = r.body && r.body.data && r.body.data.accessToken
      expect(token, `no accessToken for ${email}`).to.be.a('string')
      return token
    })

/** The claims of a JWT, so the spec can read activeOrgId and `caps` without depending on a DTO shape. */
const claims = (token) => JSON.parse(atob(token.split('.')[1]))

/**
 * Operator write to the entitlement admin API.
 *
 * `failOnStatusCode:false` because case 8 deliberately calls it as a tenant owner and expects a refusal —
 * which may be a 403 from @PreAuthorize or a non-success envelope, and both must be readable here.
 */
const setEntitlement = (token, body) =>
  cy.request({
    method: 'POST',
    url: `${GW}/api/auth/admin/entitlements`,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body,
    failOnStatusCode: false,
  })

/** Grant / revoke helpers, so the intent reads at the call site. */
const grant = (token, orgId, capability, extra) =>
  setEntitlement(token, Object.assign({ organizationId: orgId, capability, status: 'ACTIVE' }, extra || {}))
const revoke = (token, orgId, capability) =>
  setEntitlement(token, { organizationId: orgId, capability, status: 'SUSPENDED', reason: 'E1 gate' })

/**
 * Attempt a capability write as the logged-in owner, through the product's own path.
 *
 * NOT cy.setCapability — that helper asserts success, which is exactly what must not happen here. Deliberately
 * the same endpoint the Configuration screen uses, so a refusal proves the real path refuses.
 */
const writeCapability = (value) =>
  cy.request({
    method: 'POST',
    url: '/saveBusinessConfig',
    form: true,
    failOnStatusCode: false,
    body: { key: CAP_KEY, value: String(value) },
  })

describe('E1 — a tenant cannot switch on a capability it is not entitled to', () => {
  let orgId = null
  let operatorToken = null
  /** What the tenant looked like before this spec ran, so after() can put it back exactly. */
  let originalEnabled = null

  before(() => {
    gwLogin(OPERATOR, OPERATOR_PW).then((t) => {
      operatorToken = t
    })
    gwLogin(OWNER, DEMO_PW).then((t) => {
      orgId = claims(t).activeOrgId
      expect(orgId, 'owner.business@ must have an active org in its token').to.be.a('number')
    })
    cy.loginAsOwner(OWNER)
    cy.getCapabilities().then((caps) => {
      originalEnabled = caps[CAP]
    })
  })

  beforeEach(() => {
    // testIsolation clears the session cookie between tests, so authed cy.request needs the login re-run.
    cy.loginAsOwner(OWNER)
  })

  after(() => {
    // Leave no server state behind. owner.business@ is the tenant most other specs run on, and an
    // entitlement left suspended would fail them somewhere else entirely, days later.
    if (operatorToken && orgId) grant(operatorToken, orgId, CAP)
    cy.loginAsOwner(OWNER)
    if (originalEnabled !== null) cy.setCapability(CAP, originalEnabled)
  })

  // ── 1. the deploy is inert ──────────────────────────────────────────────────────────────────────

  it('⭐ inert deploy — a tenant with no licensing record loses nothing', () => {
    /*
     * THE ASSERTION THAT MUST NEVER BE WEAKENED, and the one that caught the design error twice.
     *
     * Every organization created before E1 carries plan = FREE from @Builder.Default in
     * getOrCreatePrimaryOrg — a value nothing had ever read for capability. The first design consulted the
     * PLAN on the read path, so the deploy measured every legacy tenant against a plan nobody had sold them
     * and `capability-shapes.cy.js` reported every capability off for a shop that was trading fine.
     *
     * The rule now: only POSITIVE EVIDENCE of a decision may subtract. `org_entitlement` holds DEVIATIONS,
     * so a tenant nobody has made a decision about has no rows and correctly loses nothing.
     */
    cy.getCapabilities().then((caps) => {
      expect(caps, 'the capability map').to.be.an('object')
      expect(caps[CAP], `${CAP} must survive the E1 deploy untouched`).to.eq(originalEnabled)
    })

    cy.request({
      method: 'GET',
      url: `${GW}/api/auth/admin/entitlements?organizationId=${orgId}`,
      headers: { Authorization: `Bearer ${operatorToken}` },
      failOnStatusCode: false,
    }).then((r) => {
      expect(r.body && r.body.success, `entitlements read: ${JSON.stringify(r.body)}`).to.eq(true)
      const rows = r.body.data.capabilities
      expect(rows, 'every capability is reported').to.have.length.greaterThan(0)
      rows.forEach((row) => {
        // NOT `grantable` — the plan legitimately bounds what may be switched ON, and asserting that here
        // would be asserting the pricing table rather than the migration promise.
        expect(row.revoked, `${row.capability} must not be withdrawn: ${JSON.stringify(row)}`).to.eq(false)
      })
    })
  })

  // ── 2. positive control ─────────────────────────────────────────────────────────────────────────

  it('grantable — the owner can switch the capability ON', () => {
    /*
     * Without this, a build that refused EVERY capability write would pass cases 3 to 5 perfectly. A positive
     * control has to be able to succeed for the right reason.
     */
    grant(operatorToken, orgId, CAP)
    cy.loginAsOwner(OWNER)
    writeCapability(true).then((r) => {
      expect(r.body && r.body.success, `an entitled capability must be writable: ${JSON.stringify(r.body)}`)
        .to.eq(true)
    })
    cy.getCapabilities().then((caps) => {
      expect(caps[CAP], 'and it takes effect for this session immediately').to.eq(true)
    })
  })

  // ── 3 + 4. the refusal, with a before-state that is the opposite ───────────────────────────────

  it('⭐ WITHDRAWN — switching it on is REFUSED server-side, and does not take effect', () => {
    // Before-state ON, established while still entitled, so "off afterwards" can only mean the guard fired.
    grant(operatorToken, orgId, CAP)
    cy.loginAsOwner(OWNER)
    cy.setCapability(CAP, true)
    cy.getCapabilities().then((caps) => {
      expect(caps[CAP], 'precondition: the capability is ON before the entitlement is withdrawn').to.eq(true)
    })

    // The operator withdraws it. The tenant's own write then re-mints its token, so the session sees the
    // ceiling without waiting out the 15-minute access-token lifetime.
    revoke(operatorToken, orgId, CAP)
    cy.loginAsOwner(OWNER)
    cy.setCapability(CAP, false)          // clear the switch so the attempt below is a genuine turn-ON

    writeCapability(true).then((r) => {
      const body = JSON.stringify(r.body)
      expect(r.body && r.body.success, `the write must be REFUSED: ${body}`).to.eq(false)
      // The refusal tells the owner about their PLAN — which is theirs to know — and never leaks the
      // settings namespace to anyone probing endpoints. Same rule as the anti-IDOR reads.
      expect(body, 'no settings key in an operator-facing message').to.not.contain('org.cap')
    })

    cy.getCapabilities().then((caps) => {
      expect(caps[CAP], 'the refused write must not have been applied').to.not.eq(true)
    })
  })

  // ── 5. disabling stays allowed ─────────────────────────────────────────────────────────────────

  it('WITHDRAWN — switching it OFF is still allowed', () => {
    /*
     * C6's rule, applied to entitlements. If withdrawing a capability also froze its switch, a tenant would be
     * left with a policy it can neither use nor clear, and the only way back would be a DBA.
     */
    revoke(operatorToken, orgId, CAP)
    cy.loginAsOwner(OWNER)
    writeCapability(false).then((r) => {
      expect(r.body && r.body.success, `clearing must stay possible: ${JSON.stringify(r.body)}`).to.eq(true)
    })
  })

  // ── 6. the screen — the assertion cy.request cannot make ───────────────────────────────────────

  it('⭐ the Configuration screen renders the row LOCKED, not as a control that fails on click', () => {
    /*
     * A slice is not done until something CALLS it. C6 shipped a per-product policy with a working endpoint,
     * a working guard and a green API gate — and no checkbox on any screen, so no shopkeeper could use it.
     * cy.request reaches an endpoint whether a UI exists or not; only a screen assertion can fail this way.
     */
    revoke(operatorToken, orgId, CAP)
    cy.loginAsOwner(OWNER)
    cy.visit('/businessDashboard')
    // The Configuration item lives under the Settings sub-nav; force:true because nav selects are hidden
    // until their parent opens, and a real click on a collapsed item is not what this test is about.
    cy.get('#navConfiguration').click({ force: true })

    // renderSettingsForm ids are `<fieldPrefix>_<key with non-alphanumerics replaced>`; business uses 'bcfg'.
    const rowId = '#bcfg_' + CAP_KEY.replace(/[^A-Za-z0-9]/g, '_')
    cy.get(rowId, { timeout: 15000 }).should('exist').and('be.disabled')
    cy.get(rowId)
      .closest('.cfg-row')
      .should('have.class', 'cfg-row--locked')
      .find('.cfg-row__locked')
      .should('be.visible')
  })

  // ── 7. dates are evaluated at resolve time ─────────────────────────────────────────────────────

  it('an entitlement whose end date has passed does not entitle', () => {
    /*
     * F4. A row left ACTIVE with ends_at in the past must not entitle — otherwise expiry would only ever be
     * applied by whatever job happened to rewrite the status, and a missed run would be free licensing.
     */
    grant(operatorToken, orgId, CAP, { endsAt: '2020-01-01T00:00:00' })
    cy.loginAsOwner(OWNER)
    writeCapability(true).then((r) => {
      expect(r.body && r.body.success, `an expired entitlement must not entitle: ${JSON.stringify(r.body)}`)
        .to.eq(false)
    })
  })

  // ── 8. the hole must not reopen one layer up ───────────────────────────────────────────────────

  it('⭐ a tenant OWNER cannot call the entitlement admin API', () => {
    /*
     * Company owners hold the super privilege set inside their own tenant, so gating this on ADMIN_PRIVILEGE
     * would let any owner grant themselves entitlements — the same hole, one layer up. It is gated on the
     * platform ROLE_ADMIN, exactly as provision-tenant already is.
     */
    gwLogin(OWNER, DEMO_PW).then((ownerToken) => {
      grant(ownerToken, orgId, CAP).then((r) => {
        if (r.status === 403) return
        expect(r.status, `unexpected ${r.status}: ${JSON.stringify(r.body)}`).to.be.lessThan(500)
        const b = r.body || {}
        expect(b.success === true || b.status === 'SUCCESS', `an owner was ALLOWED to grant: ${JSON.stringify(b)}`)
          .to.eq(false)
      })
    })
  })

  // ── 9. the ladder — the existing gate still holds under the new guard ──────────────────────────

  it('a plain USER of the same tenant cannot write a capability at all', () => {
    // The privilege gate must not have been weakened by the guard chain sitting in front of it. Same org,
    // different role, so a refusal here proves privilege and not tenancy.
    cy.loginAsTier('user', 'business')
    writeCapability(false).then((r) => {
      expect(r.body && r.body.success, `a USER must not write settings: ${JSON.stringify(r.body)}`).to.not.eq(true)
    })
  })
})
