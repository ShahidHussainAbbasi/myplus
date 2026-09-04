/**
 * E4 — the CONTROL-PLANE AUDIT TRAIL.
 *
 * Design:    microservices/docs/slices/e4-control-plane-audit-design.md
 * Analysis:  microservices/docs/slices/e4-control-plane-audit-analysis.md
 * Programme: microservices/docs/saas-control-plane-review.md (E0..E6)
 *
 * ── What this slice adds ────────────────────────────────────────────────────────────────────────
 * Five control-plane mutations — entitlement grant/revoke, plan change, status change, business-type
 * change, capability toggle — now leave an append-only record naming WHO changed it, on WHOSE tenant,
 * FROM what TO what, and WHY.
 *
 * ── ⚠ The property that cannot be fixed later ───────────────────────────────────────────────────
 * `audit_event` is append-only by design AND by constraint. Whatever the first release stamps into it is
 * what the trail says forever. Two of these cases exist purely to pin that down before rows accumulate:
 *
 *   case 1 + 2  the event belongs to the SUBJECT tenant, and is NOT in the operator's own trail
 *   case 4      an insider's change is distinguishable from the platform's, by a queryable column
 *
 * Case 2 is the one that carries case 1. The operator is also the reader, so an event stamped against the
 * WRONG org still comes back from `GET /api/audit` and case 1 passes on it. Only the absence proves it.
 *
 * ── ⚠ Assert the ENVELOPE, never the HTTP status ────────────────────────────────────────────────
 * A refusal arrives as 200 with `success:false` (`ProxyErrors`: "a refusal is an ANSWER, not a failure").
 * `expect(status).to.eq(200)` PASSES on a refusal. This has caught this codebase four times.
 * The exception is case 8, where the trail read is refused by `@PreAuthorize` and IS a real 403.
 *
 * ── ⚠ Delivery is ASYNCHRONOUS ──────────────────────────────────────────────────────────────────
 * The producer writes an outbox row in the mutation's transaction and delivers AFTER COMMIT; a failed
 * delivery is re-driven by a 30s relay. So every read polls with a bounded retry. A fixed `cy.wait` is
 * green on a fast machine and red on a slow one, and hides the case where only the relay saved it.
 *
 * ── Accounts ────────────────────────────────────────────────────────────────────────────────────
 *   admin@myplus.com          the PLATFORM operator (ROLE_ADMIN, never ADMIN_PRIVILEGE)
 *   owner.audit@myplus.com    ⚠ the SACRIFICIAL subject. Its plan, status and business type are changed
 *                             by this spec. NO OTHER SPEC MAY USE IT — same rule as owner.lifecycle@.
 *   owner.business@           an INSIDER, for case 4: a tenant owner acting inside their own org
 *   user.business@            the privilege-ladder rung for case 8
 */

const GW = 'http://localhost:8765'

const OPERATOR = 'admin@myplus.com'
const OPERATOR_PW = Cypress.env('adminPassword') || 'Admin@2025!'

const SUBJECT = 'owner.audit@myplus.com'
const OWNER = 'owner.business@myplus.com'
const USER = 'user.business@myplus.com'
const DEMO_PW = 'Demo@2025!'

const PORTAL = '/platformDashboard'

const CAP = 'installments'
const CAP_KEY = 'org.cap.installments'

/** A reason unique to this run, so a re-run never reads the previous one's rows. */
const RUN = `E4-${Date.now()}`

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
 * Read a trail through the gateway.
 *
 * `organizationId` is the ONB-3 operator-only parameter: honoured for ROLE_ADMIN, silently ignored for
 * everyone else (ignored rather than rejected, so a prober learns nothing). Omitting it reads the caller's
 * own tenant — which is exactly what case 2 needs.
 */
const readTrail = (token, orgId, action) =>
  cy.request({
    method: 'GET',
    url:
      `${GW}/api/audit?limit=200` +
      (orgId ? `&organizationId=${orgId}` : '') +
      (action ? `&action=${action}` : ''),
    headers: { Authorization: `Bearer ${token}` },
    failOnStatusCode: false,
  })

/**
 * Poll the subject's trail until `match` finds an event, or give up loudly.
 *
 * Recursive rather than `cy.wait(n)`: delivery is AFTER_COMMIT and normally instant, but the retry relay
 * runs every 30s and a machine under load can land in between. A fixed wait would encode whichever timing
 * the author's laptop had.
 */
const findEvent = (token, orgId, match, attempts = 20) =>
  readTrail(token, orgId).then((r) => {
    const rows = Array.isArray(r.body) ? r.body : []
    const hit = rows.find(match)
    if (hit) return cy.wrap(hit, { log: false })
    if (attempts <= 1) {
      /*
       * ⚠ NAME THE LIKELY CAUSE, not just the symptom.
       *
       * The producer writes its outbox row in the mutation's own transaction, so a control-plane change
       * that returned success has ALWAYS been recorded. If nothing arrives here, the failure is almost
       * never the assertion above it — it is DELIVERY, and delivery fails silently from the caller's side
       * by design (that is the whole point of an outbox).
       *
       * Three gate runs reported "no matching audit event" while the real cause was auth-service having no
       * INTERNAL_SECRET, so audit-service answered 403 with no body to every POST. A message that only
       * says "not found" sends the reader to the assertion, which is the one place the bug never is.
       */
      const hint =
        rows.length === 0
          ? '\n\nTHE TRAIL IS EMPTY FOR THIS TENANT. The producer records inside the mutation transaction, ' +
            'so a successful change is always captured — which makes DELIVERY the likely failure, not this ' +
            'assertion. Check, in this order:\n' +
            '  1. auth-service audit_outbox:  select status,count(*) from myplusdb_auth.audit_outbox group by status;\n' +
            '     PENDING with a rising attempt count = delivery is being refused.\n' +
            '  2. docker logs myplus-auth | grep "outbox delivery failed"  — the exception names the reason.\n' +
            '  3. A 403 with no body means audit-service rejected the identity: auth-service needs\n' +
            '     INTERNAL_SECRET set (docker-compose.yml), and the container must be RECREATED, not restarted.\n'
          : ''
      throw new Error(
        `no matching audit event after polling org ${orgId}. Last ${rows.length} rows: ` +
          JSON.stringify(rows.slice(0, 5)) +
          hint,
      )
    }
    return cy.wait(500, { log: false }).then(() => findEvent(token, orgId, match, attempts - 1))
  })

const setEntitlement = (token, body) =>
  cy.request({
    method: 'POST',
    url: `${GW}/api/auth/admin/entitlements`,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body,
    failOnStatusCode: false,
  })

const orgWrite = (token, orgId, what, body) =>
  cy.request({
    method: 'POST',
    url: `${GW}/api/auth/admin/organizations/${orgId}/${what}`,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body,
    failOnStatusCode: false,
  })

/** A capability write through the product's own path — the endpoint the Configuration screen posts to. */
const writeCapability = (value) =>
  cy.request({
    method: 'POST',
    url: '/saveBusinessConfig',
    form: true,
    failOnStatusCode: false,
    body: { key: CAP_KEY, value: String(value) },
  })

// ── the gate ──────────────────────────────────────────────────────────────────────────────────────

describe('E4 — every control-plane change leaves a record that says who, and why', () => {
  let operatorToken = null
  let operatorOrgId = null
  let subjectOrgId = null
  let ownerOrgId = null

  /** Restored in after(), so the sacrificial tenant is handed back the way it was found. */
  let originalPlan = null
  let originalShape = null
  let originalOwnerCap = null

  before(() => {
    gwLogin(OPERATOR, OPERATOR_PW).then((t) => {
      operatorToken = t
      operatorOrgId = claims(t).activeOrgId
      expect(operatorOrgId, 'the operator must have an active org in its token').to.be.a('number')
    })
    gwLogin(SUBJECT, DEMO_PW).then((t) => {
      subjectOrgId = claims(t).activeOrgId
      expect(
        subjectOrgId,
        'owner.audit@ must exist and have its own org — it is SEEDED, never provisioned by this spec',
      ).to.be.a('number')
      expect(subjectOrgId, 'the subject must NOT be the operator org').to.not.eq(operatorOrgId)
    })
    gwLogin(OWNER, DEMO_PW).then((t) => {
      ownerOrgId = claims(t).activeOrgId
    })

    // Capture the sacrificial tenant's real state, rather than assuming a default. A spec that "restores"
    // to a value it guessed leaves the tenant changed and blames the next run.
    cy.then(() =>
      cy
        .request({
          method: 'GET',
          url: `${GW}/api/auth/admin/entitlements?organizationId=${subjectOrgId}`,
          headers: { Authorization: `Bearer ${operatorToken}` },
        })
        .then((r) => {
          originalPlan = r.body.data.plan
          originalShape = r.body.data.shape
        }),
    )
    cy.loginAsOwner(OWNER)
    cy.getCapabilities().then((caps) => {
      originalOwnerCap = caps[CAP]
    })
  })

  after(() => {
    if (operatorToken && subjectOrgId) {
      if (originalPlan) orgWrite(operatorToken, subjectOrgId, 'plan', { plan: originalPlan, reason: `${RUN} cleanup` })
      if (originalShape) {
        orgWrite(operatorToken, subjectOrgId, 'shape', { shape: originalShape, reason: `${RUN} cleanup` })
      }
      orgWrite(operatorToken, subjectOrgId, 'status', { status: 'ACTIVE', reason: `${RUN} cleanup` })
      setEntitlement(operatorToken, {
        organizationId: subjectOrgId,
        capability: CAP,
        status: 'ACTIVE',
        reason: `${RUN} cleanup`,
      })
    }
    // owner.business@ is the tenant most of the suite runs on. Its capability goes back exactly.
    cy.loginAsOwner(OWNER)
    if (originalOwnerCap !== null) cy.setCapability(CAP, originalOwnerCap)
  })

  // ── 1 + 2. whose trail is it ────────────────────────────────────────────────────────────────────

  it('⭐ 1 — a grant is recorded against the SUBJECT tenant, with the operator named and the reason kept', () => {
    const reason = `${RUN} case1 — granted for the audit gate`

    setEntitlement(operatorToken, {
      organizationId: subjectOrgId,
      capability: CAP,
      status: 'ACTIVE',
      reason,
    }).then((r) => {
      expect(r.body && r.body.success, `grant: ${JSON.stringify(r.body)}`).to.eq(true)
    })

    findEvent(operatorToken, subjectOrgId, (e) => e.reason === reason).then((e) => {
      expect(e.action, 'the action names what happened').to.eq('ENTITLEMENT_GRANT')
      expect(e.entityRef, 'and which capability').to.eq(CAP)
      expect(e.organizationId, 'the event belongs to the SUBJECT tenant').to.eq(subjectOrgId)

      /*
       * The actor axis — analysis finding A1, and the reason this slice needed a migration.
       *
       * Before E4 the table had one user slot and one org slot, and every row it held had them belonging
       * together. An operator acting on tenant 44 does not, and without these two columns the trail either
       * hides the change from the customer or attributes it to one of their own staff. The second is worse:
       * an owner auditing their configuration would blame a colleague for a platform decision.
       */
      expect(e.actorType, 'a platform action is marked as one').to.eq('PLATFORM_OPERATOR')
      expect(e.actorOrgId, 'and the actor is recorded as being from OUTSIDE this tenant').to.eq(operatorOrgId)
      expect(e.userId, 'the individual operator is named, not just "the platform"').to.be.a('number')

      // Verbatim. A trail that paraphrases the reason answers a different question than the one asked.
      expect(e.reason, 'the reason is stored exactly as typed').to.eq(reason)
    })
  })

  it('⭐ 2 — that event is NOT in the operator\'s own trail', () => {
    /*
     * THE CASE THAT MAKES CASE 1 MEAN ANYTHING.
     *
     * `AuditIngestService` stamps the org from the authenticated request, and for a tenant recording its own
     * sale that is exactly right. For a control-plane event it would silently file the operator's action
     * under the operator — invisible to the customer forever, because the table is append-only.
     *
     * And the operator is also the reader here, so a wrongly-stamped event still comes back from case 1's
     * query. Only its ABSENCE from the operator's own trail distinguishes the two.
     */
    readTrail(operatorToken, null).then((r) => {
      expect(r.status, 'the operator can read their own trail').to.eq(200)
      const mine = (Array.isArray(r.body) ? r.body : []).filter((e) => String(e.reason || '').startsWith(RUN))
      expect(
        mine,
        `the subject's events must not be filed under the operator: ${JSON.stringify(mine.slice(0, 3))}`,
      ).to.have.length(0)
    })
  })

  // ── 3. before AND after ─────────────────────────────────────────────────────────────────────────

  it('⭐ 3 — a revocation records what it changed FROM, not only what it changed to', () => {
    /*
     * `EntitlementService.set` upserts straight onto the entity, so the previous value has to be read before
     * `row.setStatus(...)` or every event reads SUSPENDED → SUSPENDED. That is the single most likely
     * implementation slip in the slice, and it is invisible in a trail that records only the new value:
     * a revocation and a re-revocation look identical.
     *
     * Case 1 has already left the capability ACTIVE, so the before-state is the OPPOSITE of the after-state
     * — which is what makes this assertion evidence rather than a coincidence.
     */
    const reason = `${RUN} case3 — withdrawn`

    setEntitlement(operatorToken, {
      organizationId: subjectOrgId,
      capability: CAP,
      status: 'SUSPENDED',
      reason,
    }).then((r) => {
      expect(r.body && r.body.success, `revoke: ${JSON.stringify(r.body)}`).to.eq(true)
    })

    findEvent(operatorToken, subjectOrgId, (e) => e.reason === reason).then((e) => {
      expect(e.action).to.eq('ENTITLEMENT_REVOKE')
      expect(e.beforeValue, 'the state it was in').to.eq('ACTIVE')
      expect(e.afterValue, 'the state it is in now').to.eq('SUSPENDED')
      expect(e.beforeValue, 'a record of a change must show a change').to.not.eq(e.afterValue)
    })
  })

  // ── 4. inside vs outside ────────────────────────────────────────────────────────────────────────

  it("⭐ 4 — an OWNER's own capability toggle is recorded as an insider's, not the platform's", () => {
    /*
     * The discriminating case for the actor axis. If `actorType` were hard-coded, or derived from "there is
     * a user id", every case above would still pass — and the column would be decoration.
     *
     * ⚠ gwLogin, not cy.loginAsOwner: the latter RESTORES a cached session (cacheAcrossSpecs) and does not
     * re-login, so its JWT keeps the old `caps` claim. Here the owner must actually be able to switch the
     * capability on, which needs the entitlement granted a moment ago to be in the token.
     */
    setEntitlement(operatorToken, {
      organizationId: ownerOrgId,
      capability: CAP,
      status: 'ACTIVE',
      reason: `${RUN} case4 setup`,
    })

    cy.loginAsOwner(OWNER)
    writeCapability(true).then((r) => {
      expect(r.body && r.body.success, `the owner's own write: ${JSON.stringify(r.body)}`).to.eq(true)
    })

    gwLogin(OWNER, DEMO_PW).then((ownerToken) =>
      findEvent(
        ownerToken,
        null, // their OWN trail — no operator parameter, and none would be honoured for them anyway
        (e) => e.action === 'CAPABILITY_TOGGLE' && e.entityRef === CAP,
      ).then((e) => {
        expect(e.actorType, 'a tenant acting on itself is an insider').to.eq('MEMBER')
        expect(e.actorOrgId, 'and the actor org is the tenant itself').to.eq(ownerOrgId)
        expect(e.organizationId, 'which is also whose trail it is').to.eq(ownerOrgId)
        expect(e.afterValue, 'the switch it was moved to').to.eq('true')
      }),
    )
  })

  // ── 5. a refusal is not a change ────────────────────────────────────────────────────────────────

  it('⭐ 5 — a write REFUSED by the entitlement ceiling records nothing', () => {
    /*
     * The failure this slice is most likely to ship, because E1's whole purpose is refusing writes.
     *
     * The listener runs AFTER the upsert commits and the guard throws BEFORE it, inside the same
     * transaction — so a refusal takes the outbox row down with it. Hook it a few lines higher and the
     * trail fills with configuration changes that never happened, which is worse than no trail: it is a
     * trail that lies, and nothing downstream can tell.
     *
     * Counted rather than searched, because "no NEW event" is the claim; the capability legitimately has
     * older events from cases 1 and 3.
     */
    setEntitlement(operatorToken, {
      organizationId: ownerOrgId,
      capability: CAP,
      status: 'SUSPENDED',
      reason: `${RUN} case5 — withdraw so the owner's write is refused`,
    })

    gwLogin(OWNER, DEMO_PW).then((ownerToken) =>
      readTrail(ownerToken, null, 'CAPABILITY_TOGGLE').then((before) => {
        const n = (Array.isArray(before.body) ? before.body : []).length

        cy.loginAsOwner(OWNER)
        writeCapability(true).then((r) => {
          // The envelope, not the status. A refusal is 200 with success:false.
          expect(r.body && r.body.success, `an unentitled write must be refused: ${JSON.stringify(r.body)}`)
            .to.eq(false)
        })

        // Long enough that a delivery would have happened if one had been enqueued at all — the relay's
        // AFTER_COMMIT path is immediate, so this is not a race, it is a settling period.
        cy.wait(2000)
        gwLogin(OWNER, DEMO_PW).then((t2) =>
          readTrail(t2, null, 'CAPABILITY_TOGGLE').then((after) => {
            const m = (Array.isArray(after.body) ? after.body : []).length
            expect(m, 'a refused write must not appear in the trail').to.eq(n)
          }),
        )
      }),
    )
  })

  // ── 6. two records, on purpose ──────────────────────────────────────────────────────────────────

  it('6 — a business-type change writes BOTH an audit event and a shape-history row', () => {
    /*
     * Ruling D-3. They answer different questions and both are load-bearing: `org_shape_history` is the
     * operational state an undo will read (what this change CLEARED), the audit event is the trail (who,
     * why, when). Asserted together so a later "these look redundant" cleanup fails here rather than
     * removing the half somebody depends on.
     */
    const reason = `${RUN} case6 — onboarded under the wrong template`
    const target = originalShape === 'pharmacy' ? 'retail' : 'pharmacy'

    orgWrite(operatorToken, subjectOrgId, 'shape', { shape: target, reason }).then((r) => {
      expect(r.body && r.body.success, `shape change: ${JSON.stringify(r.body)}`).to.eq(true)
    })

    findEvent(operatorToken, subjectOrgId, (e) => e.reason === reason).then((e) => {
      expect(e.action).to.eq('SHAPE_CHANGE')
      expect(e.beforeValue, 'the type it was').to.eq(originalShape)
      expect(e.afterValue, 'the type it is now').to.eq(target)
    })

    cy.request({
      method: 'GET',
      url: `${GW}/api/auth/admin/organizations/${subjectOrgId}/shape-history`,
      headers: { Authorization: `Bearer ${operatorToken}` },
    }).then((r) => {
      const rows = (r.body.data && r.body.data.rows) || []
      expect(rows.length, 'ONB-3 still records what the change cleared').to.be.greaterThan(0)
    })
  })

  // ── 7. delivered once ───────────────────────────────────────────────────────────────────────────

  it('7 — the retry relay does not duplicate a delivered event', () => {
    /*
     * `audit_event` is unique on (organization_id, event_key) and the producer generates the key once, in
     * the outbox row — so a redelivery is a no-op at the far end rather than a second row. Asserted end to
     * end rather than by unit test, because the property depends on the producer, the relay and the ingest
     * agreeing, and a unit test on any one of them would pass while the trail double-counted.
     *
     * A plan change is used because it is the one event with an obvious duplicate signature.
     */
    const reason = `${RUN} case7 — exactly once`
    const target = originalPlan === 'PRO' ? 'TRIAL' : 'PRO'

    orgWrite(operatorToken, subjectOrgId, 'plan', { plan: target, reason }).then((r) => {
      expect(r.body && r.body.success, `plan change: ${JSON.stringify(r.body)}`).to.eq(true)
    })

    findEvent(operatorToken, subjectOrgId, (e) => e.reason === reason)
    // Well past the 30s relay would be too slow for a gate; the relay only re-drives rows that are still
    // PENDING, and a delivered row is POSTED. So the assertion is that nothing else arrives in a settling
    // period, which is what a double-delivery would produce.
    cy.wait(2000)
    readTrail(operatorToken, subjectOrgId, 'PLAN_CHANGE').then((r) => {
      const mine = (Array.isArray(r.body) ? r.body : []).filter((e) => e.reason === reason)
      expect(mine, 'one change, one row').to.have.length(1)
    })
  })

  // ── 8. who may read a trail ─────────────────────────────────────────────────────────────────────

  it('⭐ 8 — a plain user cannot read the tenant trail; the owner and the operator can', () => {
    /*
     * Analysis finding A3, pre-existing and fixed here: `AuditController.list` required only
     * `.authenticated()`, so any cashier could fetch every RECEIPT and PAYMENT in the org with amounts —
     * and E4 was about to add "the platform suspended you for non-payment" to the same list.
     *
     * ⚠ This one IS a real 403, not a 200-with-success-false: it is Spring Security refusing the method,
     * and it only surfaces as 403 because E2 fixed the monolith advice that answered 500 for every
     * AccessDeniedException. Both halves are asserted — a build that refused EVERYONE would pass the
     * negative alone.
     */
    gwLogin(USER, DEMO_PW).then((userToken) =>
      readTrail(userToken, null).then((r) => {
        expect(r.status, `a plain user must not read the audit trail: ${JSON.stringify(r.body)}`).to.eq(403)
      }),
    )

    gwLogin(OWNER, DEMO_PW).then((ownerToken) =>
      readTrail(ownerToken, null).then((r) => {
        expect(r.status, 'the owner reads their own trail').to.eq(200)
      }),
    )

    readTrail(operatorToken, subjectOrgId).then((r) => {
      expect(r.status, 'and the operator reads a subject tenant').to.eq(200)
    })
  })

  it('9 — anti-IDOR: the organizationId parameter is ignored for a non-operator', () => {
    /*
     * The ONB-3 rule, applied to a new endpoint: `organizationIdFor(requested)` honours the parameter only
     * for ROLE_ADMIN and silently gives everyone else their own org — ignored rather than rejected, so a
     * prober learns nothing about which ids exist.
     *
     * Without this the read fix in case 8 would be worthless: an owner who may read "their own" trail could
     * simply ask for someone else's.
     */
    gwLogin(OWNER, DEMO_PW).then((ownerToken) =>
      readTrail(ownerToken, subjectOrgId).then((r) => {
        expect(r.status, 'the request is answered, not refused').to.eq(200)
        const rows = Array.isArray(r.body) ? r.body : []
        rows.forEach((e) => {
          expect(e.organizationId, `an owner asking for org ${subjectOrgId} gets their OWN trail`).to.eq(
            ownerOrgId,
          )
        })
      }),
    )
  })

  // ── 10. the screen ──────────────────────────────────────────────────────────────────────────────

  it('⭐ 10 — the operator console SHOWS the trail, with the platform actor named', () => {
    /*
     * E2's lesson, restated: C6 shipped a policy with a green API gate and no control anywhere, and no
     * shopkeeper could reach the feature. A trail nobody can read is a table.
     *
     * The chip is asserted specifically — not merely that rows rendered — because the chip is the whole
     * point of the slice at the UI layer. "Platform" versus "This business" is what stops an owner
     * attributing a platform revocation to a colleague, and a row that renders without it is the defect.
     */
    cy.loginAsOperator()
    cy.visit(PORTAL)
    cy.get('[data-testid="tenant-row"]', { timeout: 15000 }).should('have.length.greaterThan', 1)

    // Reach the subject by search — 40 tenants, paged at 25, id DESC. Never assume a page.
    cy.get('#platSearch').clear().type('Audit')
    cy.get(`[data-testid="tenant-row"][data-org="${subjectOrgId}"]`, { timeout: 15000 }).first().click()

    cy.get('[data-testid="activity"]', { timeout: 15000 }).should('be.visible')
    cy.get('[data-testid="activity-row"]').should('have.length.greaterThan', 0)
    cy.get('[data-testid="activity"] .plat-badge--platform').should('exist').and('be.visible')

    // Before AND after, on the screen and not only in the payload — case 3's rule at the UI layer.
    cy.get('[data-testid="activity-row"]').first().find('.plat-act__delta').should('exist')

    // ⚠ No edit and no delete affordance, anywhere in the panel. The store is append-only, and a UI that
    // looks editable teaches operators to expect an undo that does not exist — which they discover during
    // an incident. The absence is the design, so it is asserted rather than assumed.
    cy.get('[data-testid="activity"]').find('.js-delete, .js-edit, [data-testid="activity-delete"]')
      .should('have.length', 0)
  })
})
