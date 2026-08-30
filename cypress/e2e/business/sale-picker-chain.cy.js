/**
 * TIER-1c — the section's pickers no longer queue behind the section's record list.
 *
 * <h3>What was wrong</h3>
 * `loadUserItems()` and `loadSellCustomers()` were called from inside the GRID's AJAX success handler, so on
 * the sale screen the customer picker could not even be REQUESTED until every sale line the tenant had ever
 * recorded had finished downloading. `getUserSell?q=-1` is uncapped — every branch of the server's
 * `visibleSells()` returns the full scoped list — and `Sell` rows are per LINE, not per invoice, so a shop at
 * 100 sales a day of 5 lines accumulates roughly 180k rows a year. The counter waited for all of it.
 *
 * Tier-1b had already made the picker reads non-blocking, which bought nothing on its own: a non-blocking
 * request that has not been ISSUED yet is just a request that has not happened. This is the other half.
 *
 * <h3>Why the delay is injected</h3>
 * Same reason as the tier-1b gate: on localhost the grid returns in milliseconds, so a spec that merely
 * opened the screen would pass whether or not the pickers were still chained behind it. Holding the grid
 * response open is what makes the ordering observable.
 *
 * <h3>⚠ The guard this spec has to replace</h3>
 * Tier-1c DELETED the `pickerPreloadPending` flag. That flag existed for a real defect: the grid's success
 * handler also runs on every `datatable.ajax.reload()`, and P6 rapid entry reloads after EVERY saved line
 * with the form still open — so without the flag the pickers were rebuilt underneath the operator mid-entry,
 * wiping the selection they were using. The flag is safe to delete only because the preload now runs from
 * `loadDataTable()` (which is what "a section was opened" means) and not from the reload path. That is a
 * claim about behaviour, so the last test asserts it directly rather than trusting the reasoning.
 */

const OWNER = 'owner.business@myplus.com'

describe('Sale screen — pickers are not chained behind the grid', () => {
  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  it('⭐ the customer picker is requested BEFORE the record list comes back', () => {
    /*
     * The ordering is asserted directly, not inferred from a stopwatch. A timing-based version ("the picker
     * filled within 5s") would pass or fail on machine speed and prove nothing about the chain.
     */
    const events = []

    // Held open for 6s. If the pickers were still chained behind this, their requests could not appear
    // until after 'grid-responded'.
    cy.intercept({ method: 'GET', url: '**/getUserSell*' }, (req) => {
      req.on('response', (res) => {
        res.setDelay(6000)
        events.push('grid-responded')
      })
    }).as('grid')

    cy.intercept({ method: 'GET', url: '**/customerOptions*' }, () => {
      events.push('customers-requested')
    }).as('customers')

    cy.visitSaleScreen()

    cy.wrap(null, { timeout: 30000 }).should(() => {
      const cust = events.indexOf('customers-requested')
      expect(cust, `events: ${events.join(' → ') || 'none'}`).to.be.greaterThan(-1)

      const grid = events.indexOf('grid-responded')
      // Either the grid has not answered yet (grid === -1, the strongest possible result), or the customer
      // request was issued first. Both mean the chain is broken; only "customers after grid" is a failure.
      if (grid !== -1) {
        expect(cust, `the picker must not wait for the grid — events: ${events.join(' → ')}`)
          .to.be.lessThan(grid)
      }
    })
  })

  it('⭐ the till is typeable while the record list is still downloading', () => {
    /*
     * The cashier-facing consequence, and the reason the grid also had to stop holding the overlay. The
     * overlay covers the whole viewport, so pickers that are loaded and ready are still unreachable while it
     * is up — breaking the chain without this would have changed nothing anyone could feel.
     */
    cy.intercept({ method: 'GET', url: '**/getUserSell*' }, (req) => {
      req.on('response', (res) => res.setDelay(6000))
    }).as('grid')

    cy.visitSaleScreen()

    cy.get('#appAjaxOverlay').should('not.be.visible')
    cy.get('.ao-box').should('not.be.visible')
    cy.get('#sellItems').should('not.be.disabled').type('3').should('have.value', '3')

    // The customer list must actually arrive during the grid's delay, not merely be requested.
    cy.get('#sellCustomerDD option', { timeout: 20000 }).should('have.length.greaterThan', 1)

    cy.wait('@grid', { timeout: 30000 })
  })

  it('the grid still fills once its response lands', () => {
    // Regression guard: tier-1c changed the grid's ajax config (`global: false`). The record list must still
    // render — a faster screen that stopped showing sales would be a worse product, not a better one.
    cy.visitSaleScreen()
    cy.get('#tableSell tbody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)
  })

  it('⭐ a grid RELOAD does not rebuild the pickers underneath the operator', () => {
    /*
     * THE DELETED GUARD, asserted as behaviour.
     *
     * P6 rapid entry reloads the grid after every saved line while the form is still open. When the preload
     * lived in the grid's success handler, each reload re-fetched the catalogue and rebuilt the pickers
     * mid-entry, wiping what the operator had selected. `pickerPreloadPending` was the flag that stopped it,
     * and tier-1c removed the flag — so the protection now comes from WHERE the preload is called, and that
     * has to be proven rather than argued.
     */
    let customerCalls = 0
    let productCalls = 0
    cy.intercept({ method: 'GET', url: '**/customerOptions*' }, () => { customerCalls += 1 }).as('customers')
    cy.intercept({ method: 'GET', url: '**/catalogProductPicker*' }, () => { productCalls += 1 }).as('products')

    cy.visitSaleScreen()
    cy.get('#sellCustomerDD option', { timeout: 30000 }).should('have.length.greaterThan', 1)

    cy.then(() => {
      // Snapshot AFTER the section has fully opened, so the section's own legitimate preload is not counted
      // as a reload.
      const custBefore = customerCalls
      const prodBefore = productCalls

      cy.window().then((w) => {
        expect(w.datatable, 'the grid instance is reachable').to.not.eq(null)
        w.datatable.ajax.reload()
      })

      // Let the reload complete, then assert NO new picker traffic was produced by it.
      cy.get('#tableSell tbody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)
      cy.wrap(null, { timeout: 10000 }).should(() => {
        expect(customerCalls, 'a grid reload must not re-fetch customers').to.eq(custBefore)
        expect(productCalls, 'a grid reload must not re-fetch the catalogue').to.eq(prodBefore)
      })
    })
  })
})
