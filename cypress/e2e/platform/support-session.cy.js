/**
 * E5 — the SUPPORT SESSION.
 *
 * Design:    microservices/docs/slices/e5-support-session-design.md
 * Analysis:  microservices/docs/slices/e5-support-session-analysis.md
 * Programme: microservices/docs/saas-control-plane-review.md (E0..E6) — F3, the last 🔴
 *
 * ── What changes ────────────────────────────────────────────────────────────────────────────────
 * Before this slice, `CurrentUser.organizationIdFor` asked one question — "are you a platform
 * operator?" — and a yes handed over ANY tenant, for any reason, for ever. It is used in three services
 * across four endpoints, and one of them is a write. After this slice it asks whether an OPEN SESSION
 * exists for the tenant being named.
 *
 * ── ⚠ Case 1 is the one that keeps the rest honest ──────────────────────────────────────────────
 * Two green gates (`migration-safety`, `control-plane-audit`) had to learn to open a session first. That
 * is a precondition, not a weakening — they assert the same things through the path the product now has.
 * Case 1 is what stops that reasoning being abused: it asserts the STANDING GRANT IS GONE. A build that
 * quietly restored "ROLE_ADMIN reaches everything" would pass every other case in this file.
 *
 * ── ⚠ A session only reaches services through a re-minted token ─────────────────────────────────
 * The scope travels as a claim (like `caps`, C3c), so opening a session does nothing for a token already
 * in hand. `POST /support-sessions` therefore RETURNS a fresh access token, and every case below uses the
 * token the open call handed back — never the one it started with. A spec that reused the old token would
 * fail for a reason that has nothing to do with authorization.
 *
 * ── ⚠ Assert the ENVELOPE, never the HTTP status, on proxied writes ─────────────────────────────
 * A refusal is 200 with `success:false`. Cases 1 and 4 are the exception and genuinely non-2xx, because
 * a cross-tenant read without a session resolves to the CALLER'S OWN org — see the note on case 1.
 *
 * ── Accounts ────────────────────────────────────────────────────────────────────────────────────
 *   admin@myplus.com        the platform operator (ROLE_ADMIN, never ADMIN_PRIVILEGE)
 *   owner.audit@myplus.com  the sacrificial subject — its policy flags are cleared by case 8
 *   owner.business@         a second tenant, so case 4 can prove a session does NOT open it
 *   user.business@          the privilege-ladder rung
 */

const GW = 'http://localhost:8765'

const OPERATOR = 'admin@myplus.com'
const OPERATOR_PW = Cypress.env('adminPassword') || 'Admin@2025!'
const SUBJECT = 'owner.audit@myplus.com'
const OWNER = 'owner.business@myplus.com'
const USER = 'user.business@myplus.com'
const DEMO_PW = 'Demo@2025!'

const PORTAL = '/platformDashboard'
const RUN = `E5-${Date.now()}`

// ── plumbing ──────────────────────────────────────────────────────────────────────────────────────

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

const claims = (token) => JSON.parse(atob(token.split('.')[1]))

/**
 * Open a support session and yield the RE-MINTED token that carries its scope.
 *
 * Fails loudly rather than yielding undefined: a helper that quietly returned the old token would make
 * every later case fail as an authorization bug, which is the most expensive kind of wrong diagnosis.
 */
const openSession = (token, orgId, reason, minutes) =>
  cy
    .request({
      method: 'POST',
      url: `${GW}/api/auth/admin/support-sessions`,
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: { organizationId: orgId, reason, minutes: minutes || 30 },
      failOnStatusCode: false,
    })
    .then((r) => {
      expect(r.body && r.body.success, `open session: ${JSON.stringify(r.body)}`).to.eq(true)
      const d = r.body.data
      expect(d.accessToken, 'the open call must hand back a token carrying the scope').to.be.a('string')
      expect(claims(d.accessToken).supportOrg, 'and that token must name the subject').to.eq(orgId)
      return cy.wrap({ id: d.id, token: d.accessToken, expiresAt: d.expiresAt }, { log: false })
    })

const closeSession = (token, id) =>
  cy.request({
    method: 'POST',
    url: `${GW}/api/auth/admin/support-sessions/${id}/close`,
    headers: { Authorization: `Bearer ${token}` },
    failOnStatusCode: false,
  })

/** A cross-tenant READ — the catalog policy count the business-type preview uses. */
const policyCounts = (token, orgId) =>
  cy.request({
    method: 'GET',
    url: `${GW}/api/catalog/products/policy-counts?organizationId=${orgId}`,
    headers: { Authorization: `Bearer ${token}` },
    failOnStatusCode: false,
  })

/** The cross-tenant WRITE — the one thing on the platform that changes a customer's own records. */
const clearFlags = (token, orgId) =>
  cy.request({
    method: 'POST',
    url: `${GW}/api/catalog/products/clear-tracking-flags?organizationId=${orgId}&capability=serialTracking`,
    headers: { Authorization: `Bearer ${token}` },
    failOnStatusCode: false,
  })

const mySessions = (token) =>
  cy.request({
    method: 'GET',
    url: `${GW}/api/auth/support-sessions/mine`,
    headers: { Authorization: `Bearer ${token}` },
    failOnStatusCode: false,
  })

// ── the gate ──────────────────────────────────────────────────────────────────────────────────────

describe('E5 — an operator reaches a customer only through an open, explained session', () => {
  let operatorToken = null
  let subjectOrgId = null
  let ownerOrgId = null
  const opened = []

  before(() => {
    gwLogin(OPERATOR, OPERATOR_PW).then((t) => {
      operatorToken = t
    })
    gwLogin(SUBJECT, DEMO_PW).then((t) => {
      subjectOrgId = claims(t).activeOrgId
      expect(subjectOrgId, 'owner.audit@ is SEEDED, never provisioned here').to.be.a('number')
    })
    gwLogin(OWNER, DEMO_PW).then((t) => {
      ownerOrgId = claims(t).activeOrgId
    })
  })

  after(() => {
    // Leave no server state behind: an operator session left open is a standing grant by another name,
    // which is the very thing this slice exists to remove.
    if (operatorToken) opened.forEach((id) => closeSession(operatorToken, id))
  })

  // ── 1. the standing grant is gone ───────────────────────────────────────────────────────────────

  it('⭐ 1 — without a session, an operator does NOT reach another tenant', () => {
    /*
     * THE CASE THAT KEEPS THE REST HONEST.
     *
     * Two shipped gates were changed to open a session before calling these endpoints. That is legitimate
     * — they assert the same things through the path the product now has — but the reasoning would excuse
     * almost anything if nothing pinned down that the OLD path is closed. This does.
     *
     * ⚠ It is NOT asserted as a refusal. organizationIdFor IGNORES an id the caller may not have, resolving
     * to their own org, so the call SUCCEEDS and answers about the operator's own (empty) organization. A
     * prober learns nothing — not even whether the other tenant exists — which is the anti-IDOR rule ONB-3
     * established. So the assertion is that the answer is not the SUBJECT'S.
     */
    policyCounts(operatorToken, subjectOrgId).then((r) => {
      expect(r.status, 'the request is answered, not refused').to.eq(200)
      expect(
        r.body && r.body.data && r.body.data.organizationId,
        `a session-less operator must not be answered about tenant ${subjectOrgId}: ${JSON.stringify(r.body)}`,
      ).to.not.eq(subjectOrgId)
    })
  })

  // ── 2. a reason, from the API ───────────────────────────────────────────────────────────────────

  it('⭐ 2 — opening a session without a reason is refused', () => {
    /*
     * E2's rule, restated where it matters most: a UI-only requirement is not a requirement, because the
     * endpoint is reachable without the screen and the callers that skip it are the ones nobody remembers
     * writing. An unexplained look at a customer's books is the thing this whole slice exists to prevent.
     */
    cy.request({
      method: 'POST',
      url: `${GW}/api/auth/admin/support-sessions`,
      headers: { Authorization: `Bearer ${operatorToken}`, 'Content-Type': 'application/json' },
      body: { organizationId: subjectOrgId, minutes: 30 },
      failOnStatusCode: false,
    }).then((r) => {
      expect(r.body && r.body.success, `a reasonless session must be refused: ${JSON.stringify(r.body)}`)
        .to.eq(false)
    })
  })

  // ── 3. positive control ─────────────────────────────────────────────────────────────────────────

  it('3 — with a session open, the same read succeeds', () => {
    // Without this, a build that refused EVERY cross-tenant read would pass cases 1, 4 and 5 perfectly.
    openSession(operatorToken, subjectOrgId, `${RUN} case3 — positive control`).then((s) => {
      opened.push(s.id)
      policyCounts(s.token, subjectOrgId).then((r) => {
        expect(r.body && r.body.success, `read with a session: ${JSON.stringify(r.body)}`).to.eq(true)
        expect(r.body.data.organizationId, 'and it answers about the SUBJECT').to.eq(subjectOrgId)
      })
    })
  })

  // ── 4. the narrowing is real ────────────────────────────────────────────────────────────────────

  it('⭐ 4 — a session for one tenant does not open another', () => {
    /*
     * The case that stops a session becoming a new master key. If the scope were checked as "is any session
     * open?" rather than "is a session open for THIS tenant?", every case above would still pass and the
     * slice would have changed nothing but the paperwork.
     */
    openSession(operatorToken, subjectOrgId, `${RUN} case4 — scoped to one`).then((s) => {
      opened.push(s.id)
      policyCounts(s.token, ownerOrgId).then((r) => {
        expect(r.status, 'answered, not refused — the same anti-IDOR rule as case 1').to.eq(200)
        expect(
          r.body && r.body.data && r.body.data.organizationId,
          `a session on ${subjectOrgId} must not open ${ownerOrgId}`,
        ).to.not.eq(ownerOrgId)
      })
    })
  })

  // ── 5. it ends ──────────────────────────────────────────────────────────────────────────────────

  it('⭐ 5 — a session expires, and the read stops working', () => {
    /*
     * "Time-boxed" asserted by the clock rather than by the presence of a field. A session whose expiry is
     * stored and never consulted looks identical in every other test.
     *
     * One minute is the shortest the API accepts; the spec waits it out rather than manipulating a server
     * setting, so nothing has to be restored afterwards.
     */
    openSession(operatorToken, subjectOrgId, `${RUN} case5 — expires`, 1).then((s) => {
      opened.push(s.id)
      policyCounts(s.token, subjectOrgId).then((r) => {
        expect(r.body.data.organizationId, 'open to begin with — the before-state must be the opposite')
          .to.eq(subjectOrgId)
      })

      cy.wait(62000)

      policyCounts(s.token, subjectOrgId).then((r) => {
        expect(r.status, 'still answered').to.eq(200)
        expect(
          r.body && r.body.data && r.body.data.organizationId,
          'an EXPIRED session must not still reach the tenant, token or no token',
        ).to.not.eq(subjectOrgId)
      })
    })
  })

  // ── 6. it is recorded ───────────────────────────────────────────────────────────────────────────

  it('6 — opening a session is recorded against the SUBJECT tenant', () => {
    const reason = `${RUN} case6 — recorded`
    openSession(operatorToken, subjectOrgId, reason).then((s) => {
      opened.push(s.id)
      // Reuses E4's trail wholesale: same store, same actor axis, same subject-tenant stamping.
      cy.request({
        method: 'GET',
        url: `${GW}/api/audit?organizationId=${subjectOrgId}&limit=200`,
        headers: { Authorization: `Bearer ${s.token}` },
      }).then((r) => {
        const rows = Array.isArray(r.body) ? r.body : []
        const hit = rows.find((e) => e.reason === reason)
        expect(hit, `no SUPPORT_OPENED event for this session: ${rows.length} rows`).to.exist
        expect(hit.action).to.eq('SUPPORT_OPENED')
        expect(hit.organizationId, 'filed against the customer, not the operator').to.eq(subjectOrgId)
        expect(hit.actorType, 'and marked as an outsider').to.eq('PLATFORM_OPERATOR')
      })
    })
  })

  // ── 7. the customer can see it ──────────────────────────────────────────────────────────────────

  it('⭐ 7 — the TENANT can see the session on their own screen', () => {
    /*
     * The half of "audited" that means anything to the person being supported. E4 built the record and
     * stopped here deliberately; a trail only the platform can read is a filing cabinet, not accountability.
     */
    const reason = `${RUN} case7 — visible to the customer`
    openSession(operatorToken, subjectOrgId, reason).then((s) => {
      opened.push(s.id)
      gwLogin(SUBJECT, DEMO_PW).then((tenantToken) =>
        mySessions(tenantToken).then((r) => {
          expect(r.body && r.body.success, `tenant session list: ${JSON.stringify(r.body)}`).to.eq(true)
          const rows = (r.body.data && r.body.data.rows) || []
          const hit = rows.find((x) => x.reason === reason)
          expect(hit, 'the customer must be able to see that this happened').to.exist
          expect(hit.open, 'and that it is still open').to.eq(true)
          expect(hit.operatorEmail, 'and who it was').to.be.a('string')
        }),
      )
    })
  })

  // ── 8. the write ────────────────────────────────────────────────────────────────────────────────

  it('⭐ 8 — the cross-tenant WRITE needs the customer\'s approval, and is audited', () => {
    /*
     * The single most consequential thing an operator can do to a customer's records, and until this slice
     * the one thing nothing recorded at all — catalog-service had no audit producer.
     *
     * ⚠ Asserted in both directions. A build that refused the write outright would pass the first half and
     * has broken the feature; one that allowed it always would pass the second.
     */
    const reason = `${RUN} case8 — the write`
    openSession(operatorToken, subjectOrgId, reason).then((s) => {
      opened.push(s.id)

      clearFlags(s.token, subjectOrgId).then((r) => {
        expect(r.body && r.body.success, `a read-only session must refuse the write: ${JSON.stringify(r.body)}`)
          .to.eq(false)
      })

      // The customer allows changes, and only then does it go through.
      gwLogin(SUBJECT, DEMO_PW).then((tenantToken) =>
        cy
          .request({
            method: 'POST',
            url: `${GW}/api/auth/support-sessions/${s.id}/approve-writes`,
            headers: { Authorization: `Bearer ${tenantToken}` },
            failOnStatusCode: false,
          })
          .then((r) => {
            expect(r.body && r.body.success, `approve: ${JSON.stringify(r.body)}`).to.eq(true)
          }),
      )

      /*
       * ⚠ THE OPERATOR TAKES A FRESH TOKEN, and this is the product's behaviour rather than a test
       * convenience.
       *
       * The session scope — including whether writes are allowed — travels as a CLAIM, the same design as
       * capabilities (C3c) and for the same reason: no service acquires a request-path dependency on
       * auth-service. The documented cost, named in the design's §2, is that a change to a session reaches
       * the operator at their next token refresh. The console does that automatically the moment approval
       * arrives, so a person never sees the gap; a spec has to do it explicitly.
       *
       * The half that matters is already asserted above: before approval the write is REFUSED.
       */
      let approvedToken = null
      gwLogin(OPERATOR, OPERATOR_PW).then((t) => {
        approvedToken = t
        expect(claims(t).supportOrg, 'the fresh token still carries the open session').to.eq(subjectOrgId)
        expect(claims(t).supportWrite, 'and now carries the approval').to.eq(true)
      })

      cy.then(() =>
        clearFlags(approvedToken, subjectOrgId).then((r) => {
          expect(r.body && r.body.success, `an approved session may write: ${JSON.stringify(r.body)}`)
            .to.eq(true)
        }),
      )

      cy.then(() =>
        cy.request({
          method: 'GET',
          url: `${GW}/api/audit?organizationId=${subjectOrgId}&action=CATALOG_POLICY_CLEARED&limit=50`,
          headers: { Authorization: `Bearer ${approvedToken}` },
        }),
      ).then((r) => {
        const rows = Array.isArray(r.body) ? r.body : []
        expect(rows.length, 'the write must leave a record — it left none before this slice')
          .to.be.greaterThan(0)
        expect(rows[0].actorType).to.eq('PLATFORM_OPERATOR')
      })
    })
  })

  // ── 9. the ladder ───────────────────────────────────────────────────────────────────────────────

  it('⭐ 9 — a tenant owner cannot open a session over anyone, including themselves', () => {
    // ROLE_ADMIN, never ADMIN_PRIVILEGE — every owner holds the privilege inside their own organization,
    // so a privilege gate here would let every customer grant themselves a support session over another.
    gwLogin(OWNER, DEMO_PW).then((ownerToken) => {
      cy.request({
        method: 'POST',
        url: `${GW}/api/auth/admin/support-sessions`,
        headers: { Authorization: `Bearer ${ownerToken}`, 'Content-Type': 'application/json' },
        body: { organizationId: subjectOrgId, reason: `${RUN} case9`, minutes: 30 },
        failOnStatusCode: false,
      }).then((r) => {
        expect(r.status, 'an owner must be refused outright').to.eq(403)
      })
      cy.request({
        method: 'POST',
        url: `${GW}/api/auth/admin/support-sessions`,
        headers: { Authorization: `Bearer ${ownerToken}`, 'Content-Type': 'application/json' },
        body: { organizationId: ownerOrgId, reason: `${RUN} case9 self`, minutes: 30 },
        failOnStatusCode: false,
      }).then((r) => {
        expect(r.status, 'including over their own organization').to.eq(403)
      })
    })

    gwLogin(USER, DEMO_PW).then((userToken) =>
      cy
        .request({
          method: 'GET',
          url: `${GW}/api/auth/support-sessions/mine`,
          headers: { Authorization: `Bearer ${userToken}` },
          failOnStatusCode: false,
        })
        .then((r) => {
          expect(r.status, "a plain user cannot read their org's support history").to.eq(403)
        }),
    )
  })

  // ── 10. the screen ──────────────────────────────────────────────────────────────────────────────

  it('⭐ 10 — the console shows an unmissable session bar with the tenant and a countdown', () => {
    /*
     * E2's lesson, and the reason this case exists at all: C6 shipped a policy with a green API gate and no
     * control anywhere. A session an operator cannot see is one they forget they are inside.
     */
    openSession(operatorToken, subjectOrgId, `${RUN} case10 — the bar`).then((s) => {
      opened.push(s.id)

      cy.loginAsOperator()
      cy.visit(PORTAL)
      cy.get('[data-testid="tenant-row"]', { timeout: 15000 }).should('have.length.greaterThan', 1)
      cy.get('#platSearch').clear().type('Audit')
      cy.get(`[data-testid="tenant-row"][data-org="${subjectOrgId}"]`, { timeout: 15000 }).first().click()

      cy.get('[data-testid="support-bar"]', { timeout: 15000 }).should('be.visible')
      // The tenant's NAME, not its id: an operator reading "49" has to look it up to know whose data it is.
      cy.get('[data-testid="support-bar"]').should('contain.text', 'Audit')
      // A countdown that moves. A static "expires 14:35" is not read; a number that ticks is.
      cy.get('[data-testid="support-remaining"]')
        .invoke('text')
        .should('match', /\d+:\d{2}/)
      cy.get('[data-testid="support-close"]').should('be.visible')
    })
  })
})
