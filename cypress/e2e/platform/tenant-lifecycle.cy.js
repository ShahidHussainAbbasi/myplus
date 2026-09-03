/**
 * E3 — TENANT LIFECYCLE: an operator can stop a customer trading, and start them again.
 *
 * Design:   microservices/docs/slices/e3-tenant-lifecycle-design.md
 * Analysis: microservices/docs/slices/e3-tenant-lifecycle-analysis.md
 *
 * ── What this gates ─────────────────────────────────────────────────────────────────────────────
 * `Organization.status` was written "ACTIVE" at creation, printed on E2's tenant list, and enforced NOWHERE.
 * A customer who never paid kept trading indefinitely; the only lever was revoking thirteen capabilities one
 * at a time while they carried on selling.
 *
 * ── ⭐ THE SACRIFICIAL TENANT ────────────────────────────────────────────────────────────────────
 * Suspending a tenant locks out every user in it, so this spec must never touch one another spec uses.
 * `owner.lifecycle@myplus.com` is seeded for exactly this purpose and is documented in SetupDataLoader as
 * off-limits to everything else.
 *
 * ⚠ Provisioning a throwaway tenant from the spec instead does NOT work, and the failure is the subtle kind:
 * `provisionTenant` deliberately issues NO password (the owner sets their own via a reset email), so a login
 * attempt fails on CREDENTIALS long before the status check is reached. Every case below would go green
 * against a tenant that was never actually suspended — a suite passing for the wrong reason.
 *
 * ── ⚠ The refusal is at the DOOR, not on a request ──────────────────────────────────────────────
 * There is no per-request check: a suspended tenant simply cannot obtain a token. So the assertion is on
 * LOGIN, not on some later call — and it is made through the gateway, which is where a token is minted.
 *
 * ── ⚠ Assert the ENVELOPE, never the HTTP status, on the proxied writes ─────────────────────────
 * A refused write is 200 with `success:false`. Login is different and genuinely non-2xx — the gateway refuses
 * to mint. Both shapes appear below; each assertion says which it is asserting and why.
 */

const GW = 'http://localhost:8765'
const OPERATOR = 'admin@myplus.com'
const OPERATOR_PW = Cypress.env('adminPassword') || 'Admin@2025!'
const OWNER = 'owner.business@myplus.com'
const DEMO_PW = 'Demo@2025!'

/** Seeded to be locked out. See SetupDataLoader — no other spec may use it. */
const VICTIM = 'owner.lifecycle@myplus.com'

const setStatus = (body) =>
  cy.request({ method: 'POST', url: '/platform/status', form: true, failOnStatusCode: false, body })

const setPlan = (body) =>
  cy.request({ method: 'POST', url: '/platform/plan', form: true, failOnStatusCode: false, body })

/** A raw gateway login attempt — never cy.session, which would cache and mask the very thing under test. */
const tryLogin = (email, password) =>
  cy.request({
    method: 'POST',
    url: `${GW}/api/auth/login`,
    headers: { 'Content-Type': 'application/json' },
    body: { email, password },
    failOnStatusCode: false,
  })

const claims = (token) => JSON.parse(atob(token.split('.')[1]))

describe('E3 — tenant lifecycle', () => {
  let orgId = null
  let operatorOrgId = null

  before(() => {
    /*
     * Read the victim's org id from its OWN token — and in doing so, prove it can log in before anything
     * suspends it. A precondition that is asserted rather than assumed: if this account were already
     * suspended by a crashed earlier run, every case below would "pass" without proving anything.
     */
    tryLogin(VICTIM, DEMO_PW).then((r) => {
      expect(r.status, `${VICTIM} must be able to log in BEFORE this spec suspends it: ${JSON.stringify(r.body)}`)
        .to.eq(200)
      orgId = claims(r.body.data.accessToken).activeOrgId
      expect(orgId, 'the victim tenant has an org').to.be.a('number')
    })

    // The operator's own org — case 7 needs it, and it must never end the run suspended.
    tryLogin(OPERATOR, OPERATOR_PW).then((r) => {
      operatorOrgId = claims(r.body.data.accessToken).activeOrgId
    })
  })

  after(() => {
    /*
     * Leave no server state behind. The disposable tenant is reactivated rather than left suspended so a
     * re-run starts clean, and the operator's own org is force-reactivated in case case 8 ever regresses
     * into actually applying the suspension it is supposed to refuse.
     */
    cy.loginAsOperator()
    if (orgId) setStatus({ organizationId: orgId, status: 'ACTIVE', reason: 'E3 gate cleanup' })
    if (operatorOrgId) setStatus({ organizationId: operatorOrgId, status: 'ACTIVE', reason: 'E3 gate cleanup' })
    // And prove the cleanup worked, rather than hoping: a victim left suspended makes the NEXT run's
    // before() fail with a precondition error instead of a mysterious cascade of green-for-nothing tests.
    tryLogin(VICTIM, DEMO_PW).then((r) => {
      expect(r.status, 'the victim tenant must be usable again after this spec').to.eq(200)
    })
  })

  // ── the lever ───────────────────────────────────────────────────────────────────────────────────

  it('⭐ 1 — a SUSPENDED tenant cannot obtain a token', () => {
    /*
     * The whole slice. Note the assertion is on LOGIN: there is no per-request check anywhere, by design.
     * A suspended tenant is refused at the door, and any session already open dies when it next fails to
     * refresh — within the 15-minute access-token lifetime, at zero hot-path cost.
     *
     * Asserted on the disposable tenant's OWNER, who is the person a suspension is really aimed at.
     */
    cy.loginAsOperator()
    setStatus({ organizationId: orgId, status: 'SUSPENDED', reason: 'E3 gate — non-payment' }).then((r) => {
      expect(r.body && r.body.success, `suspend: ${JSON.stringify(r.body)}`).to.eq(true)
    })

    tryLogin(VICTIM, DEMO_PW).then((r) => {
      expect(r.status, `a suspended tenant must not be issued a token: ${JSON.stringify(r.body)}`)
        .to.not.eq(200)
    })
  })

  it('⭐ 2 — reactivating restores access', () => {
    /*
     * A one-way door is not a lever, it is an accident waiting to happen. A wrong suspension stops a real
     * business trading, so the round trip is asserted rather than assumed — this is the case that makes the
     * feature safe to use.
     *
     * Asserts a REAL successful login, not merely the absence of a suspension message. "The refusal changed"
     * is satisfied by any number of other failures; "a token was issued" is satisfied by exactly one thing.
     */
    cy.loginAsOperator()
    setStatus({ organizationId: orgId, status: 'ACTIVE', reason: 'E3 gate — payment received' }).then((r) => {
      expect(r.body && r.body.success, `reactivate: ${JSON.stringify(r.body)}`).to.eq(true)
    })

    tryLogin(VICTIM, DEMO_PW).then((r) => {
      expect(r.status, `reactivation must restore access: ${JSON.stringify(r.body)}`).to.eq(200)
      expect(r.body.data.accessToken, 'and a real token is issued').to.be.a('string')
    })
  })

  it('3 — the refusal names the state and the way back', () => {
    /*
     * MaxTheService has no in-product billing (E1 §13), so unlike Shopify's "frozen" store there is no
     * payment screen to preserve access to. The message IS the remediation path — an owner who is told only
     * "invalid credentials" has no idea they need to phone anybody.
     */
    cy.loginAsOperator()
    setStatus({ organizationId: orgId, status: 'SUSPENDED', reason: 'E3 gate' })

    tryLogin(VICTIM, DEMO_PW).then((r) => {
      const msg = String((r.body && r.body.message) || '')
      expect(msg.toLowerCase(), `the refusal must say the account is suspended: "${msg}"`).to.contain('suspend')
      expect(msg, `and must name who to contact: "${msg}"`).to.match(/maxtheservice/i)
    })
  })

  it('4 — CLOSED also refuses', () => {
    // The second state is real, not decorative: CLOSED and SUSPENDED differ in intent, and a report that
    // cannot separate churn from dunning is a report nobody can act on.
    cy.loginAsOperator()
    setStatus({ organizationId: orgId, status: 'CLOSED', reason: 'E3 gate — customer left' }).then((r) => {
      expect(r.body && r.body.success, `close: ${JSON.stringify(r.body)}`).to.eq(true)
    })
    tryLogin(VICTIM, DEMO_PW).then((r) => {
      expect(r.status, 'a closed tenant must not be issued a token').to.not.eq(200)
    })
    setStatus({ organizationId: orgId, status: 'ACTIVE', reason: 'E3 gate cleanup' })
  })

  // ── validation ──────────────────────────────────────────────────────────────────────────────────

  it('5 — an unknown status is refused, not silently stored', () => {
    // `status` is free text on the column, exactly as `plan` was before E2 closed F2. An operator typing
    // "SUSPEND" must be told, not left believing a customer is stopped when they are still trading.
    cy.loginAsOperator()
    setStatus({ organizationId: orgId, status: 'SUSPEND', reason: 'E3 gate' }).then((r) => {
      expect(r.body && r.body.success, `an unknown status must be refused: ${JSON.stringify(r.body)}`)
        .to.eq(false)
    })
    tryLogin(VICTIM, DEMO_PW).then((r) => {
      const msg = String((r.body && r.body.message) || '')
      expect(msg.toLowerCase(), 'and must not have taken effect').to.not.contain('suspend')
    })
  })

  it('6 — a status change without a reason is refused', () => {
    // Every control-plane write carries a reason, enforced by the API and not the form — which is what lets
    // E4 audit by listening rather than by retrofit.
    cy.loginAsOperator()
    setStatus({ organizationId: orgId, status: 'SUSPENDED' }).then((r) => {
      expect(r.body && r.body.success, `a reasonless status change must be refused: ${JSON.stringify(r.body)}`)
        .to.eq(false)
    })
  })

  // ── ⭐ the foot-gun, guarded twice ───────────────────────────────────────────────────────────────

  it('⭐ 7 — the operator is REFUSED when suspending their own tenant', () => {
    /*
     * A console that can lock its own operator out of the console that would undo it is a foot-gun with no
     * undo. Guarded twice on purpose: refused here, and even if a suspension reached the operator's org by
     * some other route, ROLE_ADMIN is exempt at the door (case 8).
     */
    cy.loginAsOperator()
    setStatus({ organizationId: operatorOrgId, status: 'SUSPENDED', reason: 'E3 gate' }).then((r) => {
      expect(r.body && r.body.success, `suspending one's own tenant must be refused: ${JSON.stringify(r.body)}`)
        .to.eq(false)
    })
  })

  it('8 — the operator can still reach the console after a refused self-suspension', () => {
    /*
     * The visible half of the foot-gun guard. Case 7 refuses the write; this proves the refusal left NOTHING
     * behind — the operator's own organization is untouched and the console still opens.
     *
     * ⚠ The OTHER half — that ROLE_ADMIN is exempt at the DOOR even if their org were somehow suspended — is
     * asserted in `AuthServiceStatusGuardTest`, NOT here. To test it end to end this spec would have to
     * actually suspend the operator's organization, which needs a `force` flag to get past case 7. Inventing
     * an override that defeats a safety guard, purely so a test can reach a state the product refuses to
     * create, makes the product worse to make the test easier. The unit test constructs that state directly
     * and costs the product nothing.
     */
    cy.loginAsOperator()
    cy.request({ url: '/platform/organizations', failOnStatusCode: false }).then((r) => {
      expect(r.body && r.body.success, `the operator console must still answer: ${JSON.stringify(r.body)}`)
        .to.eq(true)
    })
  })

  // ── the cross-tenant gate ───────────────────────────────────────────────────────────────────────

  it('9 — a tenant OWNER cannot change any tenant\'s status', () => {
    // Same rule as every other operator endpoint: ROLE_ADMIN, never ADMIN_PRIVILEGE — which every owner
    // holds inside their own organization.
    cy.loginAsOwner(OWNER)
    setStatus({ organizationId: orgId, status: 'SUSPENDED', reason: 'should be refused' }).then((r) => {
      if (r.status === 403) return
      expect(r.status, `unexpected ${r.status}: ${JSON.stringify(r.body)}`).to.be.lessThan(500)
      expect(r.body && r.body.success, `an owner was ALLOWED to suspend a tenant: ${JSON.stringify(r.body)}`)
        .to.not.eq(true)
    })
  })

  // ── the screen ──────────────────────────────────────────────────────────────────────────────────

  it('⭐ 10 — a suspended tenant is BADGED on the operator\'s list', () => {
    /*
     * A screen assertion, which is the one an API gate cannot make: C6 shipped a per-product policy with a
     * working endpoint, a working guard and a green API test — and no control on any screen.
     *
     * Searched for by name, because the disposable tenant is the newest and the list is paged at 25.
     */
    cy.loginAsOperator()
    setStatus({ organizationId: orgId, status: 'SUSPENDED', reason: 'E3 gate' })

    cy.visit('/platformDashboard')
    cy.get('#platSearch', { timeout: 15000 }).type('Lifecycle')
    cy.get('[data-testid="tenant-row"]', { timeout: 15000 }).should('have.length.greaterThan', 0)
    cy.get('[data-testid="status-suspended"]').should('exist').and('be.visible')

    setStatus({ organizationId: orgId, status: 'ACTIVE', reason: 'E3 gate cleanup' })
  })

  // ── the axes stay separate ──────────────────────────────────────────────────────────────────────

  it('11 — changing the plan does NOT reactivate a suspended tenant', () => {
    /*
     * Status and plan are separate axes (Q3). An implicit reactivation is the kind of side effect nobody
     * predicts: an operator upgrading a suspended customer's plan in preparation for their return would
     * silently let them back in before payment cleared.
     */
    cy.loginAsOperator()
    setStatus({ organizationId: orgId, status: 'SUSPENDED', reason: 'E3 gate' })
    setPlan({ organizationId: orgId, plan: 'PRO', reason: 'E3 gate — prepared for return' }).then((r) => {
      expect(r.body && r.body.success, `the plan change itself must succeed: ${JSON.stringify(r.body)}`).to.eq(true)
    })

    tryLogin(VICTIM, DEMO_PW).then((r) => {
      const msg = String((r.body && r.body.message) || '')
      expect(msg.toLowerCase(), 'the tenant must still be suspended after a plan change').to.contain('suspend')
    })
  })
})
