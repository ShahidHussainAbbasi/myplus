/**
 * ONB-1 — the BUSINESS TYPE is chosen at onboarding, not discovered afterwards.
 *
 * Design: microservices/docs/slices/onb-1-business-type-at-onboarding.md
 *
 * ── The defect this gates ───────────────────────────────────────────────────────────────────────
 * `provisionTenant` wrote an organization and a membership and NO `org.shape` row. `Shape.byCode(null)`
 * returns GENERAL, whose preset is every capability — so every customer onboarded through the operator form
 * met the whole product. A pesticide dealer was shown Installment plans and serial/IMEI.
 *
 * The mechanism to prevent it shipped in C4 and was green the whole time. Two slices built the halves and
 * nothing connected them.
 *
 * ── ⭐ Case 5 asserts a DELIBERATE REVERSAL of a C4 rule ────────────────────────────────────────
 * C4 said an explicit tenant override always beats the shape preset, so that picking a profile could never
 * silently destroy a deliberate choice. Changing a shape now RE-APPLIES the preset — behind a confirmation
 * that names what will change. The objection was the word "silently"; the dialog removes it. What C4 produced
 * instead was a shape change that appeared to do nothing, which is its own trap and the one actually hit.
 *
 * ── ⚠ Provisioned tenants have NO password ──────────────────────────────────────────────────────
 * `provisionTenant` issues none — the owner sets their own by reset email. So a provisioned tenant can never
 * be asserted through a LOGIN. Every case below reads its capabilities through the operator's own view of
 * that tenant, which is the same resolver the tenant's token is minted from.
 */

const GW = 'http://localhost:8765'
const OPERATOR = 'admin@myplus.com'
const OPERATOR_PW = Cypress.env('adminPassword') || 'Admin@2025!'
const OWNER = 'owner.business@myplus.com'
const DEMO_PW = 'Demo@2025!'

const stamp = Date.now()

const provision = (body) =>
  cy.request({
    method: 'POST',
    url: '/platform/provisionTenant',
    form: true,
    failOnStatusCode: false,
    body: Object.assign({ firstName: 'ONB', lastName: 'Test', userType: 'BUSINESS', plan: 'PRO' }, body),
  })

const setShape = (body) =>
  cy.request({ method: 'POST', url: '/platform/shape', form: true, failOnStatusCode: false, body })

/**
 * The operator's view of one tenant's capabilities — the SAME resolver that mints the tenant's token, so
 * this is the tenant's real answer and not a second opinion.
 */
const capsOf = (orgId) =>
  cy.request({
    method: 'GET',
    url: `/platform/entitlements?organizationId=${orgId}`,
    failOnStatusCode: false,
  }).then((r) => {
    expect(r.body && r.body.success, `entitlements for ${orgId}: ${JSON.stringify(r.body)}`).to.eq(true)
    const byCode = {}
    r.body.data.capabilities.forEach((c) => { byCode[c.capability] = c })
    return byCode
  })

const gwLogin = (email, password) =>
  cy.request({
    method: 'POST', url: `${GW}/api/auth/login`,
    headers: { 'Content-Type': 'application/json' },
    body: { email, password }, failOnStatusCode: false,
  })

const claims = (token) => JSON.parse(atob(token.split('.')[1]))

describe('ONB-1 — the business type at onboarding', () => {
  beforeEach(() => cy.loginAsOperator())

  // ── the defect the owner reported ───────────────────────────────────────────────────────────────

  it('⭐ 1 — a tenant provisioned as PHARMACY does not get installments or serial tracking', () => {
    /*
     * THE CASE. A pesticide dealer, a veterinary counter or a chemist has no use for either, and before this
     * slice every one of them was shown both — because the tenant was never asked what it was.
     */
    provision({
      email: `onb.pharmacy.${stamp}@myplus.com`,
      organizationName: `ONB Pharmacy ${stamp}`,
      shape: 'pharmacy',
    }).then((r) => {
      expect(r.body && r.body.success, `provision: ${JSON.stringify(r.body)}`).to.eq(true)
      const orgId = r.body.data.organizationId

      capsOf(orgId).then((caps) => {
        expect(capOff(caps, 'installments'), 'installments must be OFF for a pharmacy').to.eq(true)
        expect(capOff(caps, 'serialTracking'), 'serial/IMEI must be OFF for a pharmacy').to.eq(true)
        expect(capOff(caps, 'conditionGrading'), 'condition grading must be OFF for a pharmacy').to.eq(true)

        // ...and the things a dispensing counter DOES need are on, or the shape did nothing useful.
        expect(capOff(caps, 'batchTracking'), 'batch tracking must be ON').to.eq(false)
        expect(capOff(caps, 'expiryTracking'), 'expiry tracking must be ON').to.eq(false)
        expect(capOff(caps, 'looseSelling'), 'loose selling must be ON').to.eq(false)
      })
    })
  })

  it('⭐ 2 — provisioning WITHOUT a business type is refused', () => {
    // An optional field is a skipped field, and the defect returns for the next customer. Xero and QuickBooks
    // both make this mandatory for the same reason: "everything on" is never right for a real business.
    provision({
      email: `onb.noshape.${stamp}@myplus.com`,
      organizationName: `ONB NoShape ${stamp}`,
    }).then((r) => {
      expect(r.body && r.body.success, `a tenant without a business type must be refused: ${JSON.stringify(r.body)}`)
        .to.eq(false)
    })
  })

  it('3 — an unknown business type is refused, not resolved to "everything on"', () => {
    /*
     * `Shape.byCode` falls back PERMISSIVELY to GENERAL — deliberately, so an unreadable stored value can
     * never strip a working tenant's screens. That is right for a READ and wrong at this write: it would turn
     * an operator's typo into "show this customer the entire product", which is the exact defect.
     */
    provision({
      email: `onb.bogus.${stamp}@myplus.com`,
      organizationName: `ONB Bogus ${stamp}`,
      shape: 'chemist',
    }).then((r) => {
      expect(r.body && r.body.success, `an unknown shape must be refused: ${JSON.stringify(r.body)}`).to.eq(false)
    })
  })

  it('4 — a tenant provisioned as RETAIL does get installments', () => {
    // The positive control. Without it, a build that switched everything off for everyone would sail through
    // case 1 looking like a fix.
    provision({
      email: `onb.retail.${stamp}@myplus.com`,
      organizationName: `ONB Retail ${stamp}`,
      shape: 'retail',
    }).then((r) => {
      expect(r.body && r.body.success, `provision: ${JSON.stringify(r.body)}`).to.eq(true)
      capsOf(r.body.data.organizationId).then((caps) => {
        expect(capOff(caps, 'installments'), 'a retail counter sells on terms').to.eq(false)
        expect(capOff(caps, 'batchTracking'), 'and has no use for batches').to.eq(true)
      })
    })
  })

  // ── the reversal ────────────────────────────────────────────────────────────────────────────────

  it('⭐ 5 — changing the business type RE-APPLIES the preset', () => {
    /*
     * The deliberate reversal of C4's "an explicit override always wins".
     *
     * The before-state is established as the OPPOSITE: the tenant is given an explicit override switching ON
     * a capability the target shape excludes. "Off afterwards" proves nothing unless it was on first — the
     * lesson C6 taught this codebase twice.
     */
    provision({
      email: `onb.switch.${stamp}@myplus.com`,
      organizationName: `ONB Switch ${stamp}`,
      shape: 'retail',
    }).then((r) => {
      const orgId = r.body.data.organizationId

      // An explicit choice by the tenant. Under C4's rule this would survive any shape change for ever.
      setShape({ organizationId: orgId, shape: 'retail', reason: 'ONB gate baseline' })
      cy.request({
        method: 'POST', url: '/platform/entitlement', form: true, failOnStatusCode: false,
        body: { organizationId: orgId, capability: 'installments', status: 'ACTIVE', reason: 'ONB gate' },
      })
      capsOf(orgId).then((caps) => {
        expect(capOff(caps, 'installments'), 'precondition: installments is ON before the shape changes')
          .to.eq(false)
      })

      setShape({ organizationId: orgId, shape: 'pharmacy', reason: 'ONB gate — corrected trade' }).then((res) => {
        expect(res.body && res.body.success, `shape change: ${JSON.stringify(res.body)}`).to.eq(true)
      })

      capsOf(orgId).then((caps) => {
        expect(capOff(caps, 'installments'), 'the new shape must re-apply — installments goes OFF').to.eq(true)
        expect(capOff(caps, 'expiryTracking'), 'and what the new shape needs comes ON').to.eq(false)
      })
    })
  })

  it('6 — a business-type change without a reason is refused', () => {
    // Consistent with plan, status and entitlement writes, so E4 audits all four by listening.
    provision({
      email: `onb.noreason.${stamp}@myplus.com`,
      organizationName: `ONB NoReason ${stamp}`,
      shape: 'retail',
    }).then((r) => {
      setShape({ organizationId: r.body.data.organizationId, shape: 'pharmacy' }).then((res) => {
        expect(res.body && res.body.success, `a reasonless shape change must be refused: ${JSON.stringify(res.body)}`)
          .to.eq(false)
      })
    })
  })

  it('⭐ 7 — re-applying a preset cannot grant an UNENTITLED capability', () => {
    /*
     * The ceiling still wins. Clearing overrides writes nothing, so E1's `revoked` is still consulted — a
     * tenant cannot acquire a withdrawn capability by picking a shape that happens to include it.
     *
     * Without this case, "re-apply the preset" would be a back door around the entitlement ceiling, which is
     * the most valuable thing the control plane has.
     */
    provision({
      email: `onb.ceiling.${stamp}@myplus.com`,
      organizationName: `ONB Ceiling ${stamp}`,
      shape: 'pharmacy',
    }).then((r) => {
      const orgId = r.body.data.organizationId

      cy.request({
        method: 'POST', url: '/platform/entitlement', form: true, failOnStatusCode: false,
        body: { organizationId: orgId, capability: 'looseSelling', status: 'SUSPENDED', reason: 'ONB gate' },
      })

      // Re-apply a shape whose preset INCLUDES looseSelling. The preset must not out-rank the revocation.
      setShape({ organizationId: orgId, shape: 'pharmacy', reason: 'ONB gate — re-apply' })

      capsOf(orgId).then((caps) => {
        expect(capOff(caps, 'looseSelling'),
          'a revoked capability must stay off, whatever the preset says').to.eq(true)
      })
    })
  })

  // ── the demo tenants ────────────────────────────────────────────────────────────────────────────

  it('⭐ 8 — the seeded pesticide tenant looks like an agri-chem counter', () => {
    /*
     * The thing the owner will look at first, and the reason this slice exists. `owner.pesticide@` and
     * `owner.mobile@` are seeded to BE those businesses; until now both were `general`, so neither had ever
     * looked like the business it represents — in a demo or in a test.
     *
     * Read from the TENANT's own token, which is the answer that actually drives its screens.
     */
    gwLogin('owner.pesticide@myplus.com', DEMO_PW).then((r) => {
      expect(r.status, `pesticide login: ${JSON.stringify(r.body)}`).to.eq(200)
      const caps = String(claims(r.body.data.accessToken).caps || '')

      expect(caps, 'no installments for a pesticide dealer').to.not.contain('installments')
      expect(caps, 'no serial/IMEI either').to.not.contain('serialTracking')
      expect(caps, 'but batch tracking, yes').to.contain('batchTracking')
      expect(caps, 'and expiry').to.contain('expiryTracking')
    })
  })

  // ── the screen ──────────────────────────────────────────────────────────────────────────────────

  it('9 — the business-type control is on the provisioning form', () => {
    // A screen assertion: an API-only gate passes whether or not an operator can actually reach the field,
    // which is how C6 shipped a policy with no control anywhere.
    cy.visit('/platformDashboard')
    cy.get('#platProvisionBtn', { timeout: 15000 }).click()
    cy.get('#provShape').should('be.visible')
    cy.get('#provShape option').should('have.length.greaterThan', 3)
  })

  // ── the tenant's own door ───────────────────────────────────────────────────────────────────────

  it('⭐ 10 — the tenant changing its OWN business type also re-applies the preset', () => {
    /*
     * The two doors must behave identically. Before this, the Configuration screen posted `org.shape` like
     * any other setting: it changed the FALLBACK and left every org.cap.* override standing, so an owner
     * picking "Pharmacy" watched nothing happen — the complaint that started this slice, arriving through
     * the door the customer is most likely to use.
     *
     * Runs on owner.business@ and restores it, because this is the tenant most other specs depend on.
     */
    cy.loginAsOwner(OWNER)
    cy.clearCapabilityOverrides()
    cy.setCapability('installments', true)
    cy.getCapabilities().then((caps) => {
      expect(caps.installments, 'precondition: installments is ON before the type changes').to.eq(true)
    })

    cy.request({
      method: 'POST', url: '/saveBusinessShape', form: true, failOnStatusCode: false,
      body: { shape: 'pharmacy' },
    }).then((r) => {
      expect(r.body && r.body.success, `tenant shape change: ${JSON.stringify(r.body)}`).to.eq(true)
    })

    cy.getCapabilities().then((caps) => {
      expect(caps.installments, 'the explicit override must have been cleared by the re-apply').to.eq(false)
      expect(caps.expiryTracking, 'and the new preset applies').to.eq(true)
    })

    // Restore: owner.business@ is a general-purpose fixture and must not be left as a pharmacy.
    cy.request({ method: 'POST', url: '/saveBusinessShape', form: true, body: { shape: 'general' } })
    cy.clearCapabilityOverrides()
  })

  // ── the remediation worklist ────────────────────────────────────────────────────────────────────

  it('11 — a tenant that was never asked is badged "No business type"', () => {
    /*
     * 37 of 41 tenants have no shape row: they were onboarded before this slice and nothing backfills them,
     * because nothing safely could — the platform cannot know what trade a customer is in, and guessing would
     * put a pharmacy on retail and hide its expiry tracking.
     *
     * The badge is what turns that into a worklist with a visible finish line. It reads the RAW `shapeSet`,
     * not the effective shape: "never asked" and "deliberately chose General business" both resolve to
     * GENERAL, and only the raw answer separates them.
     */
    cy.loginAsOperator()
    cy.request({ url: '/platform/organizations?size=100', failOnStatusCode: false }).then((r) => {
      expect(r.body && r.body.success, `list: ${JSON.stringify(r.body)}`).to.eq(true)
      const rows = r.body.data.rows
      rows.forEach((row) => {
        expect(row.shapeSet, `${row.name} must report whether a type was ever set`).to.be.a('boolean')
      })
      const unset = rows.filter((row) => row.shapeSet === false)
      expect(unset.length, 'fixture check: there are tenants still to remediate').to.be.greaterThan(0)
    })

    cy.visit('/platformDashboard')
    cy.get('[data-testid="no-business-type"]', { timeout: 15000 }).should('exist').and('be.visible')
  })
})

/**
 * Is this capability OFF for the tenant?
 *
 * The operator's row carries `revoked` (the platform withdrew it) and `grantable` (the plan allows it). What
 * the tenant actually SEES is neither on its own — it is the resolver's answer, which the row reports as
 * `enabled`. Falls back to deriving it so a missing field fails loudly rather than reading as `undefined`,
 * which would make every assertion below pass for nothing.
 */
function capOff(caps, code) {
  const row = caps[code]
  expect(row, `capability "${code}" is missing from the operator's view`).to.be.an('object')
  expect(row.enabled, `capability "${code}" has no 'enabled' field: ${JSON.stringify(row)}`).to.be.a('boolean')
  return row.enabled === false
}
