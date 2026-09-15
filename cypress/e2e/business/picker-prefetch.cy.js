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
/**
 * Wait for the customer cache to be warm — and when it never is, SAY WHY.
 *
 * picker-prefetch.js declines on Save-Data and a hidden tab. It also declined on 2g/slow-2g until 2026-09-15, and
 * this message is what proved that wrong: cases 1, 2 and 4 went red with `shouldPrefetch=false, network=slow-2g` —
 * Chrome's estimate on a loaded local machine — and the user then removed the effectiveType rule. A bare "expected
 * false to equal true" could not tell a declined prefetch from a broken one, so the failure carries the decision and
 * its inputs. Pass/fail is unchanged.
 */
function expectWarm(label) {
  cy.window({ timeout: 20000 }).should((w) => {
    const c = w.navigator.connection || {}
    expect(w.CustomerPicker.isWarm(),
      `${label} (shouldPrefetch=${w.PickerPrefetch.shouldPrefetch()}, network=${c.effectiveType}, ` +
      `saveData=${c.saveData}, visibility=${w.document.visibilityState})`)
      .to.eq(true)
  })
}

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
    expectWarm('the customer list was fetched during idle time')
  })

  it('⭐ opening New Sale fills the customer picker with NO further request', () => {
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    expectWarm('the customer list was fetched during idle time')

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

        /*
         * A SLOW connection is NOT a reason to skip (the user's ruling, 2026-09-15). Chrome's effectiveType is an
         * estimate: on this machine it read slow-2g and then 4g seconds later, and this spec's cases 1, 2 and 4 went
         * red on it (`shouldPrefetch=false, network=slow-2g`). A slow link is exactly where a preloaded list saves
         * the most; Save-Data — the person's own request, above — is the only opt-out.
         */
        Object.defineProperty(w.navigator, 'connection', {
          configurable: true, get: () => ({ saveData: false, effectiveType: '2g' }),
        })
        expect(w.PickerPrefetch.shouldPrefetch(), 'a slow connection still prefetches').to.eq(true)

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
    expectWarm('the customer list was fetched during idle time')

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
