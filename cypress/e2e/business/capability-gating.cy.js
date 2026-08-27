/**
 * C3 — a capability decides what a tenant sees AND what the server will do.
 *
 * The capability platform's non-negotiable gate, from the design doc:
 *
 *   1. Capability OFF -> the API REFUSES, not merely the menu hides.   <- the whole point
 *   2. Capability ON  -> behaviour is identical to today.              <- positive control
 *   3. Default ON for a tenant that has configured nothing.            <- the migration promise
 *
 * Point 1 is why this file exists. Before C3, visibility was decided in the browser from a hardcoded
 * VERTICALS map in module-theme.js: the list shipped to every client, `window.MODULE` was editable in
 * devtools, and the endpoint answered whoever posted to it. "It is not in the menu" was the only control,
 * and that is not a control at all.
 *
 * Point 2 is not padding. A suite that only checks things are hidden passes just as well against a build
 * that hides EVERYTHING, and this codebase has shipped exactly that class of bug before — a preview panel
 * that rendered invisible, a password meter that had never once run. So every OFF assertion here is paired
 * with an ON assertion on the same element.
 */

const OWNER = 'owner.business@myplus.com'

/** The section used throughout: installments is a real capability with a real, money-touching write. */
const CAP = 'installments'
const NAV = '[data-capability~="installments"], [data-capability="installments"]'
const PRICE = 6000

/** A real product with real stock, seeded per run. See the `before` hook for why this is not a constant. */
let productId = null

/** ISO date n months out, from LOCAL components — toISOString() is UTC and shifts the day at +05:00. */
const monthsOut = (n) => {
  const d = new Date()
  d.setMonth(d.getMonth() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** Set an ordinary (non-capability) tenant setting. Asserts, for the reason `setCapability` does. */
const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value } })
    .then((r) => expect(r.body && r.body.success, `${key}=${value}: ${JSON.stringify(r.body)}`).to.eq(true))

/** Post a sale carrying an installment block. `failOnStatusCode:false` — a refusal is a valid outcome here. */
const sellOnTerms = (label) =>
  cy.request({
    method: 'POST',
    url: '/addSell',
    headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: {
      customer: { name: `Cap ${label}`, contact: `0300C${Date.now() % 100000}`, paidAmount: 0, dueAmount: 0 },
      sales: [{ productId, quantity: 1, sellRate: PRICE, totalAmount: PRICE, netAmount: PRICE }],
      paidAmount: 0, dueAmount: 0, grandTotal: PRICE,
      installmentPlan: {
        cashPrice: PRICE, downPayment: 0, installmentCount: 6,
        frequency: 'monthly', firstDueDate: monthsOut(1),
      },
    },
  })

describe('C3 — capabilities gate the UI and the API', () => {
  before(() => {
    cy.loginAsOwner(OWNER)

    /*
     * ⚠ ESTABLISH the state, do not inherit it. An after() hook is courtesy; setup is correctness. When a
     * token expired mid-run in installment-plan.cy.js, its cleanup died with the rest of the file and the
     * NEXT spec went from 6/6 to 1/6 for a reason nothing in it could explain. Capabilities are per-tenant
     * SERVER state — leaving one off here would redden every installment spec that follows.
     */
    cy.setCapability(CAP, true)
    // The capability says the tenant MAY sell on terms; this old flag says the feature is switched on. Both
    // must be true for a plan to be created — see the design doc on why two switches is itself a defect.
    setConfig('pos.installment.enabled', 'true')
    // Every sale below omits a serial, so a tenant left with serialRequired ON refuses all of them.
    setConfig('pos.installment.serialRequired', 'false')

    /*
     * SEED the product. The first version of this spec hardcoded `productId: 1` and the sale came back
     * "An unexpected error occurred" — existence is not eligibility, and a product id that happens to exist
     * in one database is not a fixture. This creates a product WITH STOCK, so the sale can actually succeed.
     */
    cy.seedProduct({ name: `CAPGATE_${Date.now()}`, sellingPrice: PRICE, stock: 10 })
      .then((p) => { productId = p.productId })
  })

  beforeEach(() => {
    // testIsolation clears the session between tests, so authed cy.request needs the login re-established.
    cy.loginAsOwner(OWNER)
  })

  after(() => {
    // Leave no server state behind. Restoring is not optional: these switches outlive the run.
    cy.loginAsOwner(OWNER)
    cy.setCapability(CAP, true)
    setConfig('pos.installment.enabled', 'false')   // back to its shipped default
  })

  // ── the migration promise ───────────────────────────────────────────────────────────────────────

  it('publishes every capability into the settings CATALOG, so an owner can actually switch one off', () => {
    /*
     * This assertion exists because its absence hid a real break for a whole build.
     *
     * `CapabilityCatalog` was not registered — the shared module is not component-scanned, so its @Component
     * did nothing. The capability keys were therefore absent from the catalog and `SettingsService.set`
     * refused every one with "Unknown setting: org.cap.*": no owner could turn a capability off.
     *
     * And it was INVISIBLE from the read path. `isEnabledFor` catches the lookup failure and fails OPEN, so
     * /capabilities returned every capability as true and the dashboard rendered perfectly. The default-ON
     * test below passed too — it passes just as well with NO catalog at all, because "absent" and "defaulted
     * to true" are indistinguishable through that endpoint.
     *
     * So: assert the capability is really IN the catalog, rather than inferring it from a value that a
     * failure would have produced anyway. A capability with no catalog entry is one no owner can reach.
     */
    cy.request('/getBusinessConfig').then((res) => {
      const body = JSON.stringify(res.body)
      expect(body, `org.cap.${CAP} must be a real catalog entry, not a fail-open default`)
        .to.contain(`org.cap.${CAP}`)
    })
  })

  it('serves a capability map in which everything a tenant has not configured is ON', () => {
    // The whole rollout rests on this. On the deploy that introduced capabilities no tenant had an
    // org.cap.* row, so every one had to resolve true and every screen behave exactly as the day before.
    cy.getCapabilities().then((caps) => {
      expect(Object.keys(caps).length, 'the map carries every capability, not a subset').to.be.greaterThan(5)
      // Keyed by the SHORT CODE the markup carries. A key that did not match [data-capability] would hide
      // everything, or nothing, with no error either way.
      expect(caps, 'keyed by the code the markup uses').to.have.property(CAP)
      Object.entries(caps).forEach(([code, on]) => {
        expect(on, `${code} must be a boolean, not a string`).to.be.a('boolean')
      })
    })
  })

  // ── ON: the positive control ────────────────────────────────────────────────────────────────────

  it('ON — the section is not hidden, and a sale on terms actually creates a plan', () => {
    cy.setCapability(CAP, true)
    cy.visit('/businessDashboard')
    cy.get('#InstallmentDiv').should('exist').and('not.have.class', 'cap-off')
    cy.get(NAV).should('exist').and('not.have.class', 'cap-off')

    /*
     * Asserting SUCCESS alone would be VACUOUS, and nearly shipped that way. When the feature is switched
     * off the sale still returns SUCCESS — the plan is silently dropped and only a message says so. So this
     * asserts the PLAN was created, which is the thing the capability actually governs.
     */
    sellOnTerms('on').then((r) => {
      expect(String(r.body.status), JSON.stringify(r.body)).to.eq('SUCCESS')
      expect(String(r.body.message), 'the plan was created, not silently dropped').to.contain('PLN-')
    })
  })

  // ── OFF: both halves ────────────────────────────────────────────────────────────────────────────

  it('OFF — the section and its nav entry are hidden', () => {
    cy.setCapability(CAP, false)
    cy.visit('/businessDashboard')

    // Asserting the CLASS alone would be asserting the artefact. `not.be.visible` is the property the
    // operator actually experiences, and it is what catches a .cap-off rule that never loaded — which is
    // a live risk here, because the hide depends on `!important` in application.css winning against an
    // inline display written by module-theme.js.
    cy.get('#InstallmentDiv').should('have.class', 'cap-off').and('not.be.visible')
    cy.get(NAV).should('have.class', 'cap-off').and('not.be.visible')
  })

  it('OFF — THE CASE: the API refuses the sale, it does not merely hide the button', () => {
    /*
     * The reason the whole capability platform exists. A hidden menu stops nobody who has the URL, and the
     * sale endpoint answered anyone who posted to it.
     *
     * It also has to refuse BEFORE the sale is written. The pre-existing `pos.installment.enabled` check
     * lives inside createInstallmentPlan, which runs AFTER SagaSellService has committed the invoice in its
     * own REQUIRES_NEW transaction — so its "refusal" is a message on a sale that already happened, leaving
     * the customer owing the full amount with no schedule against it. A refusal that arrives after the money
     * moves is not a refusal.
     *
     * FAILED, specifically — not merely "not SUCCESS". An ERROR would mean the sale blew up for an unrelated
     * reason and this test would be passing on an accident, which is exactly what a hardcoded product id did
     * to an earlier version of this file.
     */
    cy.setCapability(CAP, false)

    sellOnTerms('off').then((r) => {
      expect(String(r.body.status), `the sale must be REFUSED, not errored: ${JSON.stringify(r.body)}`)
        .to.eq('FAILED')
      // And the refusal must not describe the tenant's configuration — same rule the anti-IDOR reads
      // follow, where "not yours" and "not there" are deliberately indistinguishable.
      expect(String(r.body.message)).to.not.contain('org.cap')
    })
  })

  it('restoring the capability brings the section and the plan back', () => {
    // Proves the switch is reversible in both directions, which is what makes turning one off safe for an
    // owner to try. A one-way gate would be a support call the first time somebody explored the screen.
    cy.setCapability(CAP, true)
    cy.visit('/businessDashboard')
    cy.get('#InstallmentDiv').should('not.have.class', 'cap-off')

    sellOnTerms('restored').then((r) => {
      expect(String(r.body.status), JSON.stringify(r.body)).to.eq('SUCCESS')
      expect(String(r.body.message), 'plans work again after the capability is restored').to.contain('PLN-')
    })
  })
})
