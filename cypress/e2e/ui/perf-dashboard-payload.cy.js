/**
 * PERF review 2026-09-26 — what a dashboard open DOWNLOADS.
 *
 * Measured before: every open of /businessDashboard fetched /getUserProduct (the whole catalogue, 1.4 MB of JSON,
 * 81 KB gzipped) and /customerOptions TWICE — both for the Sale Detail Report's filter rail, which was mounted AND
 * filled at page load although the report is a separate screen most sessions never open. The rail now fills
 * lazily, from the shared ProductPicker / CustomerPicker caches (report-filters.js loadLists).
 *
 * Asserts what the defect broke — REQUESTS on the wire — and that the filter still WORKS once the report opens:
 * a lazy list that never filled would make the first two cases pass on a broken screen.
 *
 * Run headed.
 */
describe('PERF — the dashboard downloads only what it shows', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('⭐⭐ opening the dashboard does not download the whole catalogue', () => {
    let full = 0
    cy.intercept('GET', '**/getUserProduct*', (req) => { full++; req.continue() })
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.wait(3000)   // every background read the page schedules has gone out
    cy.then(() => expect(full, '/getUserProduct requests on a dashboard open').to.eq(0))
  })

  it('⭐ the customer list is read ONCE per dashboard open, not once per consumer', () => {
    let n = 0
    cy.intercept('GET', '**/customerOptions*', (req) => { n++; req.continue() })
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.wait(3000)
    cy.then(() => expect(n, '/customerOptions requests').to.be.at.most(1))
  })

  it('⭐⭐ opening the Sale Detail Report fills its Product and Customer filters — still without the full catalogue', () => {
    let full = 0
    cy.intercept('GET', '**/getUserProduct*', (req) => { full++; req.continue() })
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.get('#sellType').select('SRDiv', { force: true })
    cy.get('#SRDiv').should('be.visible')
    cy.get('#rfProduct option', { timeout: 20000 }).should('have.length.greaterThan', 1)
    cy.get('#rfCustomer option', { timeout: 20000 }).should('have.length.greaterThan', 1)
    cy.then(() => expect(full, '/getUserProduct requests').to.eq(0))
  })

  it('the product filter still FILTERS: choosing one sends its id with the report', () => {
    cy.intercept('POST', '**/loadSR*').as('sr')   // a form POST: the filters travel in the body
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.get('#sellType').select('SRDiv', { force: true })
    cy.get('#rfProduct option', { timeout: 20000 }).should('have.length.greaterThan', 1)
    cy.get('#rfProduct option').eq(1).then(($o) => {
      const id = $o.val()
      cy.get('#rfProduct').select(id, { force: true })       // a change applies the filter (onApply → loadSR)
      cy.wait('@sr').its('request.body').should('contain', `productId=${id}`)
    })
  })
})
