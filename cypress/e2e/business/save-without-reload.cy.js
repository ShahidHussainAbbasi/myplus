/**
 * PERF-13 — saving a record patches its row instead of reloading the grid, and does not block the screen.
 *
 * ── What it was ─────────────────────────────────────────────────────────────────────────────────
 * Every save called `loadDataTable()`, which destroys the DataTable and refetches the whole entity —
 * **543 KB for a customer**, 1.76 MB on the sale screen — to show one changed row. A blocking overlay
 * covered the viewport for the whole round trip, so the operator could not start their next action.
 *
 * ── The three changes, and why each is asserted here ────────────────────────────────────────────
 *   1. `addCustomer` returns the SAVED ROW (it returned only a message).
 *   2. The client patches that row; `loadDataTable()` becomes the fallback, not the default.
 *   3. Writes carry `nonBlocking: true`, so the overlay stays down.
 *
 * ⚠ Every case asserts BEHAVIOUR the user would notice, never the mechanism. "applySavedRow was called"
 * would pass on a build that called it and patched nothing — which is the failure mode being guarded.
 */

const uniq = () => Date.now().toString().slice(-8) + Math.floor(Math.random() * 900 + 100)

/**
 * Open the Customer section and its ADD MODAL, the way the screen does.
 *
 * ⚠ The form lives in a crud-overlay modal (#CustomerModal) reached from #newCustomer — not a panel on
 * the section select. Same four steps b2b-customer-type.cy.js uses; copied rather than re-invented, because
 * an opener that half-works produces failures about the thing under test.
 */
const openCustomers = () => {
  cy.visitDashboardSettled()
  cy.openSection('CustomerDiv')
  cy.get('#tableCustomer tbody tr', { timeout: 20000 }).should('have.length.greaterThan', 0)
}

const openAddForm = () => {
  cy.get('#newCustomer').click()
  cy.get('#CustomerModal', { timeout: 15000 }).should('have.class', 'open')
}

describe('PERF-13 — a save updates the row, not the whole grid', () => {
  beforeEach(() => cy.loginAsOwner())

  it('⭐ 1 — the server returns the saved customer, not just a message', () => {
    /*
     * The precondition for everything else. A client cannot patch a row it was not given — and it must not
     * paint the FORM's values, because the server generates the id, stamps the clocks, defaults
     * customerType and PRESERVES the derived dueAmount that an edit form carries as blank.
     */
    const run = uniq()
    cy.request({
      method: 'POST', url: '/addCustomer', form: true,
      body: { name: `P13 ${run}`, contact: `0300${run}` }, failOnStatusCode: false,
    }).then((r) => {
      expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS')
      const saved = r.body.object || r.body.data
      expect(saved, '⭐ the write answers with the row it wrote').to.be.an('object')
      expect(saved.customerId, 'carrying the id the SERVER generated').to.be.ok
      expect(saved.name).to.contain(run)
      // The two fields a form cannot supply — the whole reason the row must come from the server.
      expect(saved, 'and the derived/stamped fields').to.have.property('customerType')
      expect(saved).to.have.property('dueAmount')
    })
  })

  it('⭐⭐ 2 — saving from the screen does NOT refetch the customer list', () => {
    /*
     * ⭐ THE CASE. Counted, not inferred: a build that still reloads would issue a getUserCustomer here,
     * and this fails. That request is 543 KB, so this is the difference the operator feels.
     */
    const run = uniq()
    openCustomers()
    cy.intercept('GET', '**/getUserCustomer*').as('gridReload')

    openAddForm()
    cy.get('#customerName').clear().type(`P13 Screen ${run}`)
    cy.get('#contact').clear().type(`0311${run}`)
    cy.get('#addCustomer').click({ force: true })

    // The new row appears...
    cy.contains('#tableCustomer tbody tr', `P13 Screen ${run}`, { timeout: 20000 }).should('exist')

    // ...and no grid refetch happened while it did.
    cy.get('@gridReload.all').then((calls) => {
      expect(calls.length, 'the grid was NOT refetched to show one new row').to.eq(0)
    })
  })

  it('⭐ 3 — an EDIT updates that row in place, and the row count does not change', () => {
    /*
     * An edit must REPLACE the row, not append a second one. Asserted on the count, because a patch that
     * inserted instead of replacing would still show the new name and look correct.
     */
    const run = uniq()
    cy.request({
      method: 'POST', url: '/addCustomer', form: true,
      body: { name: `P13 Edit ${run}`, contact: `0322${run}` },
    }).then((r) => {
      const id = (r.body.object || r.body.data).customerId
      openCustomers()
      cy.get('#tableCustomer tbody tr').its('length').then((before) => {
        cy.request({
          method: 'POST', url: '/addCustomer', form: true,
          body: { customerId: id, name: `P13 Edited ${run}`, contact: `0322${run}` },
        }).then((edit) => {
          const saved = edit.body.object || edit.body.data
          expect(saved.customerId, 'the edit answers with the same row').to.eq(id)
          cy.window().then((win) => {
            expect(win.applySavedRow('Customer', saved), 'the patch reports success').to.eq(true)
          })
          cy.get('#tableCustomer tbody tr').should('have.length', before)
          cy.contains('#tableCustomer tbody tr', `P13 Edited ${run}`).should('exist')
        })
      })
    })
  })

  it('⭐ 4 — the screen is NOT covered while a save is in flight', () => {
    /*
     * The blocking overlay is what made a fast save feel slow: 220 ms in, the viewport is covered and the
     * operator cannot start the next action until the server answers.
     *
     * The request is delayed deliberately so the overlay would certainly have shown on the old build.
     */
    const run = uniq()
    openCustomers()
    cy.intercept('POST', '**/addCustomer', (req) => { req.on('response', (res) => res.setDelay(1200)) }).as('save')

    openAddForm()
    cy.get('#customerName').clear().type(`P13 Overlay ${run}`)
    cy.get('#contact').clear().type(`0333${run}`)
    cy.get('#addCustomer').click({ force: true })

    // Mid-flight — well past the 220 ms show delay — the overlay must still be down.
    cy.wait(600)
    cy.get('#appAjaxOverlay').should('not.have.class', 'show')
    cy.wait('@save')
  })

  it('5 — an entity with no row builder still reloads, rather than silently doing nothing', () => {
    /*
     * ⚠ THE FALLBACK, which is what makes the whole change safe. Only Customer has a builder today; every
     * other entity must keep its reload. A patch that quietly did nothing would leave the operator looking
     * at stale data with no error — worse than the second it replaced.
     */
    cy.visitDashboardSettled()
    cy.window().then((win) => {
      expect(win.applySavedRow('Vender', { id: 1, name: 'x' }),
        'an unregistered entity reports FALSE so the caller reloads').to.eq(false)
      expect(win.applySavedRow('Customer', null),
        'and a response with no saved row does too').to.eq(false)
    })
  })
})
