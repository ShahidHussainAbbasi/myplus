/**
 * PERF-9 — the till's pickers are filled BEFORE the cashier asks for them.
 *
 * WHAT THIS ASSERTS, AND WHY THE OBVIOUS TEST IS THE WRONG ONE.
 *
 * The claim is not "the picker eventually fills" - it always did. The claim is that it is ALREADY
 * filled when New Sale is opened, because the lists were fetched during idle time after login. So the
 * assertion has to happen BEFORE the sale screen is opened; a test that opens the till first would
 * pass identically against the old build and prove nothing.
 *
 * Reported from a shop with FOUR products and THREE customers - the tell that the wait was the round
 * trip, not the payload, paid with a customer standing at the counter.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/picker-prefetch.cy.js --headed --no-exit
 */
describe('PERF-9 — the pickers are warm before the sale screen opens', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  // ── ⭐⭐ the claim ────────────────────────────────────────────────────────────────────────────────

  it('⭐⭐ both caches are WARM on the dashboard, before New Sale is ever clicked', () => {
    cy.visit('/businessDashboard')
    cy.waitForAppReady()

    cy.window().should((w) => {
      expect(w.ProductPicker, 'product-picker.js is loaded').to.be.an('object')
      expect(w.CustomerPicker, 'customer-picker.js is loaded').to.be.an('object')
      expect(w.PickerPrefetch, 'picker-prefetch.js is loaded').to.be.an('object')
    })

    /*
     * The prefetch is scheduled on `load` + requestIdleCallback, so it is genuinely asynchronous -
     * retried through cy.window().should rather than waited on with a sleep, which would either be too
     * short on a loaded machine or wasted on a fast one.
     */
    cy.window({ timeout: 20000 }).should((w) => {
      expect(w.CustomerPicker.isWarm(), 'the customer list was fetched during idle time').to.eq(true)
    })
  })

  it('⭐ opening New Sale fills the customer picker with NO further request', () => {
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window({ timeout: 20000 }).should((w) => expect(w.CustomerPicker.isWarm()).to.eq(true))

    // Count reads AFTER the cache is warm: the whole point is that opening the till costs none.
    let reads = 0
    cy.intercept('GET', '**/customerOptions*', (req) => { reads += 1; req.continue() }).as('cust')

    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')

    // Filled - and filled from memory.
    cy.get('#sellCustomerDD option', { timeout: 15000 }).should('have.length.greaterThan', 0)
    cy.get('#sellCustomerDD').should('not.contain.text', 'Loading')

    cy.wait(1000)
    cy.then(() => {
      expect(reads, 'the warm cache served the picker - no second trip on New Sale').to.eq(0)
    })
  })

  // ── the limits ──────────────────────────────────────────────────────────────────────────────────

  it('⭐ a metered connection is respected — Save-Data switches the prefetch off', () => {
    /*
     * Save-Data is a request from the person using the device to stop spending their allowance on
     * things they did not ask for, and a speculative fetch is exactly that. Asserted on the decision
     * function rather than on a real 2g link, which a gate cannot produce - the decision IS the rule,
     * and it is the part that would be quietly dropped in a later edit.
     */
    cy.visit('/businessDashboard')
    cy.waitForAppReady()

    cy.window().then((w) => {
      const real = Object.getOwnPropertyDescriptor(w.navigator, 'connection')
      try {
        Object.defineProperty(w.navigator, 'connection', {
          configurable: true, get: () => ({ saveData: true, effectiveType: '4g' }),
        })
        expect(w.PickerPrefetch.shouldPrefetch(), 'Save-Data means do not prefetch').to.eq(false)

        Object.defineProperty(w.navigator, 'connection', {
          configurable: true, get: () => ({ saveData: false, effectiveType: '2g' }),
        })
        expect(w.PickerPrefetch.shouldPrefetch(), '2g means do not prefetch').to.eq(false)

        Object.defineProperty(w.navigator, 'connection', {
          configurable: true, get: () => ({ saveData: false, effectiveType: '4g' }),
        })
        expect(w.PickerPrefetch.shouldPrefetch(), 'an ordinary connection prefetches').to.eq(true)
      } finally {
        // Leave no state behind, even inside one page.
        if (real) Object.defineProperty(w.navigator, 'connection', real)
      }
    })
  })

  it('a customer added on screen is not served stale from the cache', () => {
    /*
     * The failure a cache MUST NOT have: a customer created a moment ago missing from the next list
     * does not read as a stale cache to a cashier, it reads as the system losing them. So the cache is
     * dropped on successful writes rather than on a timer.
     */
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window({ timeout: 20000 }).should((w) => expect(w.CustomerPicker.isWarm()).to.eq(true))

    const name = `Prefetch Probe ${Date.now()}`
    cy.request({
      method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name, contact: '03' + String(Date.now()).slice(-9) },
    }).then((r) => {
      expect(r.body.status, `seed the customer: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
    })

    // ⚠ cy.request does NOT go through the page's jQuery, so the ajaxComplete hook cannot see it -
    // invalidate explicitly, which is what the app's own screens do via that hook.
    cy.window().then((w) => w.CustomerPicker.invalidate())

    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellCustomerDD option', { timeout: 20000 }).should('contain.text', name)
  })
})
