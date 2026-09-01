/**
 * E2 — the OPERATOR PORTAL.
 *
 * Design:   microservices/docs/slices/e2-operator-portal-design.md
 * Analysis: microservices/docs/slices/e2-operator-portal-analysis.md
 * Programme: microservices/docs/saas-control-plane-review.md (E0..E6)
 *
 * ── What this gates ─────────────────────────────────────────────────────────────────────────────
 * E1 shipped the entitlement API and no screen, which made it the platform's eighth "works, unreachable"
 * capability. E2 gives the operator a portal: find a tenant, see its plan and capabilities, change them.
 *
 * ── ⭐ The cases that matter most are the REFUSALS ──────────────────────────────────────────────
 * The tenant list is the platform's FIRST deliberate cross-tenant read. Everything else in this codebase
 * scopes by org; this endpoint's purpose is not to. So the interesting assertion is not that an operator can
 * see 40 tenants — it is that a tenant OWNER cannot see any. An owner holds ADMIN_PRIVILEGE inside their own
 * org, which is exactly why every gate here is ROLE_ADMIN and never a privilege.
 *
 * ── ⚠ Assert the ENVELOPE, never the HTTP status ────────────────────────────────────────────────
 * A refusal arrives as 200 with `success:false` on the proxied ApiResponse routes ("a refusal is an ANSWER,
 * not a failure"). `expect(status).to.eq(200)` PASSES on a refusal. Five incidents and counting.
 *
 * ── ⚠ A DOM assertion is what an API gate cannot make ───────────────────────────────────────────
 * C6 shipped a per-product policy with a working endpoint, a working guard and a green API gate — and no
 * control on any screen. cy.request reaches an endpoint whether a UI exists or not. Cases 1, 2, 4 and 6
 * assert the SCREEN.
 *
 * ── Accounts ────────────────────────────────────────────────────────────────────────────────────
 *   admin@myplus.com   the PLATFORM operator — ROLE_ADMIN, userType ADMIN, not a customer
 *   owner.business@    a tenant owner: every privilege inside their org, none outside it
 *   admin.business@ / user.business@   the rest of the ladder (GATE-RUNBOOK rule 4)
 */

const GW = 'http://localhost:8765'
const OPERATOR = 'admin@myplus.com'
const OPERATOR_PW = Cypress.env('adminPassword') || 'Admin@2025!'
const OWNER = 'owner.business@myplus.com'
const DEMO_PW = 'Demo@2025!'

const PORTAL = '/platformDashboard'
const CAP = 'installments'

/** Monolith BFF endpoints the screen itself calls — deliberately the product's own path. */
const listOrgs = (q) =>
  cy.request({
    method: 'GET',
    url: `/platform/organizations${q ? `?q=${encodeURIComponent(q)}` : ''}`,
    failOnStatusCode: false,
  })

const setEntitlement = (body) =>
  cy.request({ method: 'POST', url: '/platform/entitlement', form: true, failOnStatusCode: false, body })

const setPlan = (body) =>
  cy.request({ method: 'POST', url: '/platform/plan', form: true, failOnStatusCode: false, body })

/** Gateway login, to read a tenant's own org id out of its token. */
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
      return r.body.data.accessToken
    })

const claims = (token) => JSON.parse(atob(token.split('.')[1]))

/** Denied = a 403, OR any 2xx whose envelope is not a success. Never a real SUCCESS. */
const expectRefused = (r, label) => {
  if (r.status === 403) return
  expect(r.status, `${label}: unexpected ${r.status} ${JSON.stringify(r.body)}`).to.be.lessThan(500)
  const b = r.body || {}
  expect(b.success === true || b.status === 'SUCCESS', `${label}: ALLOWED — the gate is not ROLE_ADMIN`)
    .to.eq(false)
}

describe('E2 — the operator portal', () => {
  let ownerOrgId = null
  let originalPlan = null

  before(() => {
    gwLogin(OWNER, DEMO_PW).then((t) => {
      ownerOrgId = claims(t).activeOrgId
      expect(ownerOrgId, 'owner.business@ must have an active org').to.be.a('number')
    })
  })

  after(() => {
    /*
     * Leave no server state behind. owner.business@ is the tenant most other specs run on: a capability left
     * suspended or a plan left changed would fail them somewhere else entirely, days later — the same failure
     * period-close once caused by leaving the books locked.
     */
    cy.loginAsOperator()
    if (ownerOrgId) {
      setEntitlement({ organizationId: ownerOrgId, capability: CAP, status: 'ACTIVE', reason: 'E2 gate cleanup' })
      if (originalPlan) setPlan({ organizationId: ownerOrgId, plan: originalPlan, reason: 'E2 gate cleanup' })
    }
  })

  // ── the operator's own path ─────────────────────────────────────────────────────────────────────

  it('⭐ 1 — the operator lands on the platform portal, not on a shopkeeper\'s till', () => {
    /*
     * ModuleRouter.DASHBOARD_BY_TYPE has no ADMIN entry, so today `getOrDefault(key, COMMERCE_DASHBOARD)`
     * routes the platform operator to /businessDashboard — a tenant's POS screen, scoped to the operator's
     * own accidental org. Not a security hole; the wrong product.
     */
    cy.loginAsOperator()
    /*
     * /dashboard is the ROUTER (AppController.dashboard → ModuleRouter.dashboardFor); "/" is the public
     * landing page and would prove nothing. This is the same method MySimpleUrlAuthenticationSuccessHandler
     * calls after a real login, so asserting it here asserts where a login lands — without depending on
     * cy.session's cached login having performed the redirect inside its own block.
     */
    cy.visit('/dashboard')
    cy.location('pathname', { timeout: 15000 }).should('eq', PORTAL)
  })

  it('⭐ 2 — the tenants list RENDERS, with more than one tenant', () => {
    // A screen assertion, not cy.request. An API gate passes whether or not a UI exists — that is exactly
    // how C6 shipped a policy no shopkeeper could set.
    cy.loginAsOperator()
    cy.visit(PORTAL)
    cy.get('[data-testid="tenant-row"]', { timeout: 15000 }).should('have.length.greaterThan', 1)
    cy.get('[data-testid="tenant-count"]').invoke('text').should('match', /\d+/)
  })

  it('3 — search narrows the list, server-side', () => {
    /*
     * Proves paging/search is not a client-side filter over everything. 40 tenants today; the query is
     * written for 40,000, and a filter that ships every row would have to be undone later.
     */
    cy.loginAsOperator()
    listOrgs().then((all) => {
      expect(all.body && all.body.success, `list: ${JSON.stringify(all.body)}`).to.eq(true)
      const total = all.body.data.total
      expect(total, 'the platform has tenants to list').to.be.greaterThan(1)

      listOrgs('mobile').then((hit) => {
        expect(hit.body.data.total, 'a search must return FEWER tenants than everything').to.be.lessThan(total)
        expect(hit.body.data.rows.length, 'and must still find the mobile tenant').to.be.greaterThan(0)
        hit.body.data.rows.forEach((row) => {
          const hay = `${row.name} ${row.ownerEmail}`.toLowerCase()
          expect(hay, `row does not match the query: ${JSON.stringify(row)}`).to.contain('mobile')
        })
      })
    })
  })

  it('4 — a LAPSED trial is badged on the screen', () => {
    /*
     * 14 of 20 TRIAL tenants are already past trial_ends_at and nothing surfaces it. They are harmless today
     * only because E1 grandfathered explicit ACTIVE rows; a NEW tenant on a lapsed trial would be refused
     * every capability with nothing on any screen explaining why.
     *
     * `trialLapsed` is computed SERVER-side — the operator and the resolver must agree what "lapsed" means.
     */
    cy.loginAsOperator()
    listOrgs().then((r) => {
      const lapsed = r.body.data.rows.filter((row) => row.trialLapsed)
      // Seeded data has lapsed trials; if it ever does not, this asserts nothing and must say so rather
      // than passing quietly.
      expect(lapsed.length, 'fixture check: at least one lapsed trial exists to badge').to.be.greaterThan(0)
    })
    cy.visit(PORTAL)
    cy.get('[data-testid="trial-lapsed"]', { timeout: 15000 }).should('exist').and('be.visible')
  })

  // ── ⭐ the refusals — the point of the slice ────────────────────────────────────────────────────

  it('⭐ 5 — a tenant OWNER is refused the tenant list', () => {
    /*
     * THE assertion. An owner holds ADMIN_PRIVILEGE inside their own org, so a privilege-gated operator
     * surface would hand every customer the list of every other customer. This is why every gate here is
     * ROLE_ADMIN, and it is the one case that proves it.
     */
    cy.loginAsOwner(OWNER)
    listOrgs().then((r) => expectRefused(r, 'owner reading the tenant list'))
  })

  it('⭐ 6 — a tenant OWNER visiting the portal does not get the page', () => {
    // The screen half of the same rule. Hiding a menu was never the control, but a page that renders for a
    // customer is a different failure again.
    cy.loginAsOwner(OWNER)
    cy.request({ url: PORTAL, failOnStatusCode: false }).then((r) => {
      expect(r.status, `owner GET ${PORTAL} → ${r.status}`).to.not.eq(200)
    })
  })

  it('7 — the rest of the ladder is refused too', () => {
    // GATE-RUNBOOK rule 4. Same org as the owner, so a refusal here proves ROLE and not tenancy.
    cy.loginAsTier('admin', 'business')
    listOrgs().then((r) => expectRefused(r, 'admin.business@ reading the tenant list'))
    cy.loginAsTier('user', 'business')
    listOrgs().then((r) => expectRefused(r, 'user.business@ reading the tenant list'))
  })

  // ── the mutations ───────────────────────────────────────────────────────────────────────────────

  it('⭐ 8 — the operator revokes a capability and the tenant loses it', () => {
    /*
     * End to end through E1's ceiling, driven from the portal's own endpoint. The before-state is
     * established as the OPPOSITE first: "off afterwards" proves nothing against something already off.
     */
    cy.loginAsOwner(OWNER)
    cy.setCapability(CAP, true)
    cy.getCapabilities().then((caps) => {
      expect(caps[CAP], 'precondition: the tenant has the capability ON').to.eq(true)
    })

    cy.loginAsOperator()
    setEntitlement({
      organizationId: ownerOrgId, capability: CAP, status: 'SUSPENDED', reason: 'E2 gate — non-payment',
    }).then((r) => {
      expect(r.body && r.body.success, `revoke: ${JSON.stringify(r.body)}`).to.eq(true)
    })

    // The tenant's own view. A fresh login re-mints the token, which is how a revoke reaches a session
    // before the 15-minute access-token lifetime elapses.
    cy.loginAsOwner(OWNER)
    cy.getCapabilities().then((caps) => {
      expect(caps[CAP], 'the revoked capability must be OFF for the tenant').to.eq(false)
    })
  })

  it('9 — a revoke without a reason is REFUSED by the API, not merely by the form', () => {
    // A UI-only requirement is not a requirement, and an unexplained revocation is unauditable the day
    // somebody asks why a customer lost a feature.
    cy.loginAsOperator()
    setEntitlement({ organizationId: ownerOrgId, capability: CAP, status: 'SUSPENDED' }).then((r) => {
      expect(r.body && r.body.success, `a reasonless revoke must be refused: ${JSON.stringify(r.body)}`)
        .to.eq(false)
    })
  })

  it('10 — the plan can be changed, and an invalid plan is refused', () => {
    /*
     * organizations.plan is a free-text String today (finding F2) — this endpoint is the only place an
     * operator writes it, so it is where the Plan enum has to be enforced.
     */
    cy.loginAsOperator()
    listOrgs().then((r) => {
      const row = r.body.data.rows.find((o) => o.id === ownerOrgId)
      expect(row, 'the owner tenant is in the list').to.be.an('object')
      originalPlan = row.plan
    })

    setPlan({ organizationId: ownerOrgId, plan: 'PRO', reason: 'E2 gate' }).then((r) => {
      expect(r.body && r.body.success, `a valid plan change: ${JSON.stringify(r.body)}`).to.eq(true)
    })
    setPlan({ organizationId: ownerOrgId, plan: 'PLATINUM_ULTRA', reason: 'E2 gate' }).then((r) => {
      expect(r.body && r.body.success, `an unknown plan must be refused: ${JSON.stringify(r.body)}`)
        .to.eq(false)
    })
  })
})
