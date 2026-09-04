/**
 * ONB-3 — nobody changes a business type without being told, in numbers, what it will stop working.
 *
 * Design: microservices/docs/slices/onb-3-migration-safety-design.md
 *
 * ── What this gates ─────────────────────────────────────────────────────────────────────────────
 * ONB-1 and ONB-2 made a business-type change easy and visible. Nothing told anyone what it would COST.
 *
 * Measured on the real data: switching `owner.mobile@` (org 44, 304 products) from retail to pharmacy turns
 * serialTracking off, and 19 of those products carry `requires_serial`. `SerialUnitService.validateForSale`
 * calls `assertEnabled(SERIAL_TRACKING)` for every one of them, so all 19 stop selling — refused at the till
 * with a message naming neither the product nor the reason.
 *
 * ── ⭐ Case 2 is the one that keeps the dialog worth reading ─────────────────────────────────────
 * A count must appear only for a capability that is ACTUALLY CHANGING, and must appear whenever one is. A
 * scary number about nothing trains people to click through; a missing number is the warning not arriving.
 * Both halves are asserted, so the case can never pass by saying nothing.
 *
 * ── ⭐ Cases 7 and 8: the org parameter is an OPERATOR's, and nobody else's ──────────────────────
 * The count endpoints take an `organizationId` because an operator legitimately asks about somebody else's
 * tenant. Case 7 is the cross-tenant READ, case 8 the cross-tenant WRITE — one query parameter would
 * otherwise let any shopkeeper clear a competitor's serial policy. They run BEFORE case 9, which clears the
 * same flags legitimately: after that there is nothing left for case 8 to protect.
 *
 * ── Case 11: a preview must never block a switch ─────────────────────────────────────────────────
 * The preview composes three services. If a count cannot be fetched the dialog opens WITHOUT it: a preview
 * that refuses to open would stop a legitimate business-type change because a reporting call was slow.
 *
 * ── ⚠ This spec DESTROYS tenant state, and puts it back ─────────────────────────────────────────
 * Case 9 bulk-clears every `requires_serial` flag `owner.mobile@` holds. That is the feature working, and it
 * is also 19 flags that `serial-register.cy.js` and the mobile-shop gates depend on. So the ids are captured
 * before the clear and restored in `after()`, and the subject of cases 1/6/9 is a product this spec SEEDS —
 * not the 19 it inherits, which a second run would no longer find. GATE-RUNBOOK §5 and §7.
 *
 * ── Tenants ─────────────────────────────────────────────────────────────────────────────────────
 *   owner.mobile@     org 44 — serial-tracked products. The subject.
 *   owner.pesticide@  org 45 — no serial-tracked products. The empty-impact control, and case 8's attacker.
 */

const OWNER_MOBILE = 'owner.mobile@myplus.com'
const OWNER_PESTICIDE = 'owner.pesticide@myplus.com'
const DEMO_PW = 'Demo@2025!'
const GW = 'http://localhost:8765'

const gwLogin = (email, password) =>
  cy.request({
    method: 'POST', url: `${GW}/api/auth/login`,
    headers: { 'Content-Type': 'application/json' },
    body: { email, password }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `login ${email}: ${JSON.stringify(r.body)}`).to.eq(200)
    return r.body.data.accessToken
  })

const claims = (t) => JSON.parse(atob(t.split('.')[1]))

/**
 * A call straight at the gateway as a given tenant.
 *
 * The Bearer token matters. `cy.loginAsMobileOwner()` establishes a MONOLITH session, which authenticates
 * nothing at the gateway — a tenancy case that reaches the gateway without a token gets 401 and passes while
 * proving only that unauthenticated calls are refused, which was never the question.
 */
const asTenant = (token, opts) =>
  cy.request(Object.assign({ failOnStatusCode: false }, opts, {
    headers: Object.assign({ Authorization: `Bearer ${token}` }, opts.headers || {}),
  }))

/** The operator's preview — the same endpoint the confirmation dialog calls. */
const preview = (orgId, shape) =>
  cy.request({
    method: 'GET',
    url: `/platform/shapePreview?organizationId=${orgId}&shape=${shape}`,
    failOnStatusCode: false,
  }).then((r) => {
    expect(r.body && r.body.success, `preview ${shape}: ${JSON.stringify(r.body)}`).to.eq(true)
    return r.body.data
  })

const setShape = (orgId, shape, reason) =>
  cy.request({
    method: 'POST', url: '/platform/shape', form: true, failOnStatusCode: false,
    body: { organizationId: orgId, shape, reason },
  })

const conflicts = (orgId) =>
  cy.request({
    url: `/platform/policyConflicts?organizationId=${orgId}&capability=serialTracking`,
    failOnStatusCode: false,
  }).then((r) => {
    expect(r.body && r.body.success, `conflicts: ${JSON.stringify(r.body)}`).to.eq(true)
    return r.body.data.rows
  })

/** Mark a product serial-tracked the way an owner does. C6-gated, so the capability must be on first. */
const requireSerial = (productId) =>
  cy.request({
    method: 'POST', url: '/setProductTracking', form: true, failOnStatusCode: false,
    body: { id: productId, requiresSerial: true },
  }).then((r) => {
    expect(r.body && r.body.success, `requiresSerial(${productId}): ${JSON.stringify(r.body)}`).to.not.eq(false)
  })

describe('ONB-3 — a business-type change tells you what it costs', () => {
  let mobileOrg = null
  let pesticideOrg = null
  let mobileTok = null
  let pesticideTok = null
  let seeded = null
  /** Every product requiring a serial before case 9 clears them, so after() can put the flags back. */
  let stranded = []

  before(() => {
    gwLogin(OWNER_MOBILE, DEMO_PW).then((t) => { mobileTok = t; mobileOrg = claims(t).activeOrgId })
    gwLogin(OWNER_PESTICIDE, DEMO_PW).then((t) => { pesticideTok = t; pesticideOrg = claims(t).activeOrgId })

    /*
     * SEED the subject rather than inherit it. The 19 products this slice was measured on are real, and case 9
     * legitimately clears them — so a run that depended on finding them would pass once and go red for ever
     * after, blaming the feature for what the previous run did (GATE-RUNBOOK §7, trap 1).
     */
    cy.loginAsMobileOwner()
    cy.setShape('retail')
    cy.setCapability('serialTracking', true)
    cy.seedProduct({ name: `ONB3_SERIAL_${Date.now()}` }).then((p) => {
      seeded = p.productId
      requireSerial(seeded)
    })
  })

  beforeEach(() => cy.loginAsOperator())

  after(() => {
    /*
     * Leave no server state behind, in the order the guards require: the SHAPE first (an operator switch
     * clears capability overrides, so restoring the capability before it would simply be undone), then the
     * capability, then the flags — /setProductTracking is C6-gated and refuses without serialTracking on.
     */
    cy.loginAsOperator()
    if (mobileOrg) setShape(mobileOrg, 'retail', 'ONB-3 gate cleanup')
    if (pesticideOrg) setShape(pesticideOrg, 'pharmacy', 'ONB-3 gate cleanup')

    cy.loginAsMobileOwner()
    cy.setCapability('serialTracking', true)
    // Case 5's clearCapabilityOverrides wipes EVERY org.cap.* row, not only the one it goes on to set. A
    // mobile shop is retail + serial + condition (SetupDataLoader says so in as many words), and the retail
    // preset carries neither, so condition grading has to be put back by name or the demo tenant stops being
    // a mobile shop until somebody restarts auth-service.
    cy.setCapability('conditionGrading', true)
    // Put back exactly what case 9 cleared. Asserted inside requireSerial: a restore that silently failed is
    // how a spec takes an unrelated one from 6/6 to 1/6 days later for a reason nothing in that file explains.
    cy.wrap(null).then(() => { stranded.forEach((id) => requireSerial(id)) })
  })

  // ── the counts ──────────────────────────────────────────────────────────────────────────────────

  it('⭐ 1 — the preview counts the products that will stop selling', () => {
    /*
     * THE CASE. An operator who sees "19 products will stop selling" before pressing the button will often
     * decide differently; one who sees "Turning OFF: Track serial / IMEI numbers" has no idea that sentence
     * means his handset stock freezes.
     */
    preview(mobileOrg, 'pharmacy').then((p) => {
      expect(p.turningOff, 'serial tracking is going off').to.satisfy(
        (list) => list.some((s) => /serial/i.test(s)))
      expect(p.impact, 'the preview carries an impact block').to.be.an('object')
      expect(p.impact.productsRequiringSerial, 'and counts the products that will stop selling')
        .to.be.greaterThan(0)
    })
  })

  it('⭐ 2 — a count appears for a changing capability, and ONLY for one', () => {
    /*
     * The case that keeps the dialog worth reading, asserted in BOTH directions so it can never pass by
     * saying nothing:
     *   serial IS changing      → the number must be there (the warning arriving)
     *   serial is NOT changing  → it must not (a scary number about nothing)
     *
     * A dialog that always warns is one people learn to click through, which costs more than never warning.
     */
    preview(mobileOrg, 'retail').then((p) => {
      const serialChanging = []
        .concat(p.turningOn || [], p.turningOff || [])
        .some((s) => /serial/i.test(s))
      const count = p.impact ? p.impact.productsRequiringSerial : undefined

      if (serialChanging) {
        expect(count, `serial tracking is changing, so the count belongs here: ${JSON.stringify(p.impact)}`)
          .to.be.a('number')
      } else {
        expect(count === undefined || count === 0,
          `serial tracking is not changing, so no serial count belongs here: ${JSON.stringify(p.impact)}`)
          .to.eq(true)
      }
    })
  })

  it('3 — open installment plans and the amount outstanding are reported', () => {
    /*
     * Measured: owner.business@ carries 206 active plans and Rs 7,716,000 outstanding. That is the money that
     * used to vanish from the dashboard when the capability went off — "the tile disappears" and "Rs 7.7m
     * stops being chased" are the same sentence, and only one of them gets acted on.
     *
     * ⚠ This is also the case that catches the BFF answering with the OPERATOR's own book: the downstream call
     * carries the operator's token, so without an explicit organizationId it reports the platform org's
     * (empty) receivables under this tenant's name — a wrong number rather than an error, which is worse.
     */
    cy.request({ url: '/platform/organizations?q=Business&size=25', failOnStatusCode: false }).then((r) => {
      const row = r.body.data.rows.find((o) => /business/i.test(o.name))
      expect(row, 'a tenant with installment history is in the list').to.be.an('object')
      preview(row.id, 'pharmacy').then((p) => {
        if ((p.turningOff || []).some((s) => /installment/i.test(s))) {
          expect(p.impact.openInstallmentPlans, 'open plans are counted').to.be.greaterThan(0)
          expect(p.impact.installmentsOutstanding, 'and so is the money still owed').to.be.greaterThan(0)
        }
      })
    })
  })

  it('4 — a tenant with no conflicts gets an EMPTY impact, not a zero-filled scare', () => {
    // owner.pesticide@ has no serial-tracked products. Nothing to warn about must produce nothing to read.
    preview(pesticideOrg, 'retail').then((p) => {
      const serial = p.impact && p.impact.productsRequiringSerial
      expect(serial === undefined || serial === 0,
        `a tenant with no serial products must not be warned about them: ${JSON.stringify(p.impact)}`).to.eq(true)
    })
  })

  // ── the memento ─────────────────────────────────────────────────────────────────────────────────

  it('⭐ 5 — the switch records the previous shape and the overrides it cleared', () => {
    /*
     * The only thing that makes a business-type change reversible. Switching back restores CAPABILITIES — the
     * shape is just a settings row and nothing is deleted — but the tenant's own switches do not come back,
     * because applyShape cleared them and nothing recorded what they were.
     *
     * The before-state is established as the opposite: an explicit override is set first, so "it was recorded"
     * cannot pass against a tenant that had nothing to record.
     */
    cy.loginAsMobileOwner()
    cy.clearCapabilityOverrides()
    cy.setShape('retail')
    cy.setCapability('serialTracking', true)

    cy.loginAsOperator()
    setShape(mobileOrg, 'pharmacy', 'ONB-3 gate — recorded switch').then((r) => {
      expect(r.body && r.body.success, `switch: ${JSON.stringify(r.body)}`).to.eq(true)
    })

    cy.request({
      url: `/platform/shapeHistory?organizationId=${mobileOrg}`, failOnStatusCode: false,
    }).then((r) => {
      expect(r.body && r.body.success, `history: ${JSON.stringify(r.body)}`).to.eq(true)
      const latest = r.body.data.rows[0]
      expect(latest, 'a history row was written').to.be.an('object')
      expect(latest.previousShape, 'the shape it came from').to.eq('retail')
      expect(latest.newShape).to.eq('pharmacy')
      expect(String(latest.previousOverrides), 'and the overrides it cleared, so an undo is possible')
        .to.contain('serialTracking')
      expect(latest.reason).to.contain('ONB-3 gate')
    })
  })

  // ── the cleanup list ────────────────────────────────────────────────────────────────────────────

  it('⭐ 6 — the cleanup list names exactly the conflicting products', () => {
    // A warning an operator cannot act on is advice, not a feature. C6 already permits clearing a product
    // policy WITHOUT the capability, precisely so nobody is stranded; what was missing is finding them.
    cy.loginAsOperator()
    setShape(mobileOrg, 'pharmacy', 'ONB-3 gate — conflicts')

    conflicts(mobileOrg).then((rows) => {
      expect(rows.length, 'the products that will not sell are listed').to.be.greaterThan(0)
      expect(rows.map((p) => p.id), 'including the one this spec marked').to.include(seeded)
      rows.forEach((p) => {
        expect(p.requiresSerial, `${p.name} is listed but does not require a serial`).to.eq(true)
      })
      // Captured for after(): case 9 is about to clear every one of these, and they belong to a tenant that
      // other specs log in as.
      stranded = rows.map((p) => p.id)
    })
  })

  // ── ⚠ tenancy: an org parameter is an OPERATOR's, and nobody else's ─────────────────────────────

  it("⭐ 7 — a tenant's own organizationId parameter is IGNORED, not honoured", () => {
    /*
     * The count endpoints take an organizationId because an operator legitimately asks about another tenant.
     * For everyone else that parameter is a cross-tenant read of a competitor's catalogue, so it resolves to
     * the caller's own org — silently, so a tenant probing with ?organizationId=45 cannot even learn whether
     * 45 exists.
     *
     * Asserted on the org the answer is ABOUT, not on a status code: an endpoint that returned 200 carrying
     * the wrong tenant's numbers is exactly the failure this guards.
     */
    asTenant(mobileTok, {
      url: `${GW}/api/catalog/products/policy-counts?organizationId=${pesticideOrg}`,
    }).then((r) => {
      expect(r.status, `policy-counts as a tenant: ${JSON.stringify(r.body)}`).to.eq(200)
      const data = r.body && r.body.data
      expect(data, 'an answer came back').to.be.an('object')
      expect(data.organizationId, 'answered about the CALLER, never the org in the URL').to.eq(mobileOrg)
      expect(data.organizationId, 'and specifically not the tenant asked about').to.not.eq(pesticideOrg)
    })
  })

  it("⭐ 8 — one tenant cannot clear ANOTHER tenant's policy flags", () => {
    /*
     * The cross-tenant WRITE, and the reason case 7 is not enough on its own. clear-tracking-flags takes the
     * same organizationId; honoured for anyone, it would let a shopkeeper strip a competitor's serial policy
     * with one query parameter — freezing nothing of their own and everything of somebody else's.
     *
     * Runs before case 9 deliberately: after the legitimate clear there is nothing left to protect.
     */
    asTenant(pesticideTok, {
      method: 'POST',
      url: `${GW}/api/catalog/products/clear-tracking-flags?organizationId=${mobileOrg}`
         + '&capability=serialTracking',
    })

    // The victim's flags must be exactly as they were. Asked as the victim, with no parameter at all.
    asTenant(mobileTok, { url: `${GW}/api/catalog/products/policy-counts` }).then((r) => {
      expect(r.body.data.requiresSerial,
        "another tenant's POST must not have cleared a single flag here").to.be.greaterThan(0)
    })
  })

  // ── the cleanup, done ───────────────────────────────────────────────────────────────────────────

  it('⭐ 9 — bulk clear removes the flags, and the list empties', () => {
    // The round trip, not just the warning. Asserting the list is empty AFTER is only evidence because case 6
    // established it was not empty before.
    cy.loginAsOperator()
    conflicts(mobileOrg).then((rows) => {
      // Belt and braces for a `.only` run: case 6 normally fills this, but after() must restore whatever this
      // case destroys however the spec was invoked.
      if (rows.length) stranded = Array.from(new Set(stranded.concat(rows.map((p) => p.id))))
    })

    cy.request({
      method: 'POST', url: '/platform/clearPolicyFlags', form: true, failOnStatusCode: false,
      body: { organizationId: mobileOrg, capability: 'serialTracking' },
    }).then((r) => {
      expect(r.body && r.body.success, `bulk clear: ${JSON.stringify(r.body)}`).to.eq(true)
    })

    conflicts(mobileOrg).then((rows) => {
      expect(rows.length, 'nothing is left demanding a serial the tenant cannot record').to.eq(0)
    })
  })

  it('⭐ 10 — clear-flags can only CLEAR, never set', () => {
    /*
     * C6's rule has to survive this endpoint. A tenant without serialTracking may not SET a product's serial
     * policy — only remove it. An endpoint that could set one would be a way round the capability, offered
     * precisely to the tenants that just lost it.
     */
    cy.loginAsOperator()
    cy.request({
      method: 'POST', url: '/platform/clearPolicyFlags', form: true, failOnStatusCode: false,
      body: { organizationId: mobileOrg, capability: 'serialTracking', value: 'true' },
    }).then(() => {
      conflicts(mobileOrg).then((rows) => {
        expect(rows.length, 'a "value" parameter must not turn the policy back on').to.eq(0)
      })
    })
  })

  // ── failure tolerance ───────────────────────────────────────────────────────────────────────────

  it('11 — a preview still opens when a count is unavailable', () => {
    /*
     * The preview composes three services. A count that cannot be fetched is OMITTED, never fatal: a preview
     * that refused to open would stop a legitimate business-type change because a reporting call was slow.
     *
     * Asserted for an organization id that exists nowhere — every count query returns nothing, which is the
     * same code path a downstream failure takes.
     */
    cy.request({
      method: 'GET', url: '/platform/shapePreview?organizationId=99999999&shape=pharmacy',
      failOnStatusCode: false,
    }).then((r) => {
      expect(r.body && r.body.success, `the dialog must still open: ${JSON.stringify(r.body)}`).to.eq(true)
      expect(r.body.data.turningOn, 'and still answer the capability question').to.be.an('array')
    })
  })

  // ── the tenant's own door ───────────────────────────────────────────────────────────────────────

  it("12 — the tenant's own Configuration screen gets the same counts", () => {
    // The owner has the same power and less context; if anything they need the numbers more.
    cy.loginAsMobileOwner()
    cy.request({ url: '/getBusinessShapePreview?shape=pharmacy', failOnStatusCode: false }).then((r) => {
      expect(r.body && r.body.success, `owner preview: ${JSON.stringify(r.body)}`).to.eq(true)
      expect(r.body.data.impact, 'the owner sees the impact too').to.be.an('object')
    })
  })

  it('⭐ 13 — installmentImpact cannot be pointed at another tenant', () => {
    /*
     * The endpoint reports somebody's RECEIVABLES. Its organizationId is honoured only for a ROLE_ADMIN
     * operator; for a tenant it resolves to their own org, or it is a cross-tenant read of a competitor's
     * debtor book with no ROLE_ADMIN anywhere near it.
     *
     * Asserted by COMPARISON, because the payload carries no org to check against: the answer with somebody
     * else's id in the URL must be identical to the answer with no id at all.
     */
    asTenant(mobileTok, { url: `${GW}/api/business/installmentImpact` }).then((own) => {
      expect(own.status, `own impact: ${JSON.stringify(own.body)}`).to.eq(200)
      asTenant(mobileTok, {
        url: `${GW}/api/business/installmentImpact?organizationId=13`,
      }).then((spoofed) => {
        expect(spoofed.status).to.eq(200)
        expect(JSON.stringify(spoofed.body.object),
          'a caller-supplied org id must change nothing about the answer')
          .to.eq(JSON.stringify(own.body.object))
      })
    })
  })

  // ── the screen ──────────────────────────────────────────────────────────────────────────────────

  it('⭐ 14 — the console SHOWS the cleanup list, and its button frees the products', () => {
    /*
     * The assertion the other thirteen cases cannot make.
     *
     * Every case above reaches an endpoint with cy.request, which succeeds whether a control exists or not.
     * C6 shipped a per-product policy with a column, an endpoint, a server guard and a fully green API gate —
     * and no checkbox anywhere for a shopkeeper to use (GATE-RUNBOOK, "an API-only gate cannot see the
     * screen"). This slice would repeat that exactly: cases 6-10 prove the cleanup ENDPOINTS work, and a
     * warning an operator cannot act on is advice, not a feature.
     *
     * SEEDS ITS OWN CONFLICT. Cases 9 and 10 deliberately leave the tenant with nothing stranded, so reading
     * whatever rows happen to be left would be the fixture trap this runbook names twice: existence is not
     * eligibility, and the row that survives an earlier case is nobody's fixture.
     */
    cy.loginAsMobileOwner()
    cy.setShape('retail')
    cy.setCapability('serialTracking', true)   // C6: a policy may only be SET while the capability is held
    requireSerial(seeded)

    // Take the capability away the way a real business-type change does — not by switching the capability
    // off directly, which would prove nothing about a migration.
    cy.loginAsOperator()
    setShape(mobileOrg, 'pharmacy', 'ONB-3 gate — the cleanup panel')

    cy.visit('/platformDashboard')
    cy.get('#platSearch', { timeout: 15000 }).type('Mobile')
    cy.get(`[data-testid="tenant-row"][data-org="${mobileOrg}"]`, { timeout: 15000 })
      .should('exist').click()

    // be.visible, not exist: a panel rendered into a hidden container is precisely the failure a DOM
    // assertion misses, and this one is appended asynchronously after the tenant detail is drawn.
    cy.get('[data-testid="policy-conflicts"]', { timeout: 15000 }).should('be.visible')
    cy.get('[data-testid="policy-conflict-count"]').invoke('text').then((n) => {
      expect(Number(n), 'the card says how many products are stranded').to.be.greaterThan(0)
    })

    cy.get('[data-testid="clear-policy"]').should('be.visible').click()
    // The shared dialog, never window.confirm. The bulk clear is one-way from here — the business type no
    // longer permits setting the requirement back — so it is confirmed rather than instant.
    cy.get('.uiC-ok', { timeout: 10000 }).should('be.visible').click()

    // The card goes because the SERVER says the list is empty: the handler re-opens the tenant rather than
    // deleting the card it just clicked, so a failed POST leaves the warning standing.
    cy.get('[data-testid="policy-conflicts"]', { timeout: 15000 }).should('not.exist')

    // And the round trip is real rather than repainted.
    conflicts(mobileOrg).then((rows) => {
      expect(rows.length, 'nothing is left demanding a serial the tenant cannot record').to.eq(0)
    })
  })
})
