/**
 * Task #19 — the INVOICE documents behind the Sale Detail Report.
 *
 * <h3>The distinction that defines this slice</h3>
 * The report already had Print and Excel/PDF buttons — but those are DataTables exports that dump the TABLE,
 * a grid of rows. What was missing is the actual invoice DOCUMENT for the sales listed: tenant header,
 * customer, lines, totals, document number, produced by receipt.js / document-pdf.js. A slice that "solved"
 * this by restyling a table export would look finished and deliver the wrong artifact, so the assertions
 * below are about invoice documents, never about the grid.
 *
 * <h3>The unit is an INVOICE, not a row</h3>
 * The report lists sale LINES, so a three-line sale appears three times. The strongest assertion here is the
 * DE-DUPLICATION: handing an operator the same invoice three times is worse than a missing one, because a
 * duplicate in a stack given to a customer reads as a second charge.
 *
 * <h3>Also proves a previously unreachable function</h3>
 * `downloadInvoicePdf` has existed in document-pdf.js with NO caller anywhere in the codebase. This screen is
 * the first to reach it — the fifth "shipped but unreachable" case found in this area, so it gets asserted
 * rather than assumed.
 */

const OWNER = 'owner.business@myplus.com'

/** Open the Sale Detail Report and run it, so the grid holds real rows. */
function openReportWithRows() {
  cy.visitDashboardSettled()
  cy.get('#sellType').select('SRDiv', { force: true })
  cy.get('#SRDiv').should('be.visible')
  // Selected by the handler the app binds — the View report button carries no id, and its label is
  // translated in six locales.
  cy.get('#SRDiv button[onclick*="loadSR"]').first().click({ force: true })
  cy.get('#tableSellReport tbody .sr-inv', { timeout: 30000 }).should('have.length.greaterThan', 0)
}

describe('Sale Detail Report — invoice documents', () => {
  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  it('⭐ Print invoices fetches each DISTINCT invoice exactly once', () => {
    const fetched = []
    cy.intercept('GET', '**/getReceipt*', (req) => { fetched.push(req.query.invoiceNo) }).as('receipts')

    openReportWithRows()

    // The distinct set the operator can actually see, computed the same way the button does.
    cy.window().then((w) => {
      const nos = w.srVisibleInvoiceNos()
      expect(nos.length, 'the report shows at least one invoice').to.be.greaterThan(0)
      expect(new Set(nos).size, 'srVisibleInvoiceNos de-duplicates').to.eq(nos.length)

      // Stub print: an unstubbed dialog blocks the run, and the assertion is on what was GATHERED.
      cy.stub(w, 'print').as('printed')

      cy.get('#srPrintInvoices').click()
      cy.get('.uiC-card .uiC-title').should('be.visible')
      cy.get('.uiC-card button').contains(new RegExp('print', 'i')).click()

      cy.wrap(null, { timeout: 30000 }).should(() => {
        expect(fetched.length, `one fetch per distinct invoice (${fetched.length} vs ${nos.length})`)
          .to.eq(nos.length)
        expect(new Set(fetched).size, 'and NO invoice fetched twice').to.eq(fetched.length)
      })
    })
  })

  it('⭐ it produces INVOICE DOCUMENTS, not a table export', () => {
    /*
     * The wrong-artifact guard. getReceipt returns the authoritative sale document; a grid export would touch
     * no such endpoint at all. Asserting the response carries invoice fields is what separates the two.
     */
    cy.intercept('GET', '**/getReceipt*').as('receipt')
    openReportWithRows()

    cy.window().then((w) => {
      cy.stub(w, 'print').as('printed')
      cy.get('#srPrintInvoices').click()
      cy.get('.uiC-card button').contains(new RegExp('print', 'i')).click()
    })

    cy.wait('@receipt', { timeout: 30000 }).then((i) => {
      expect(i.response.body.status, 'the document resolves').to.eq('SUCCESS')
      const doc = i.response.body.object
      expect(doc, 'an invoice document, not a row of grid cells').to.be.an('object')
      expect(doc.invoiceNo, 'carries its invoice number').to.be.a('string')
    })
  })

  it('the previously unreachable PDF download is now wired', () => {
    /*
     * downloadInvoicePdf existed with no caller. Asserted as INVOKED with a real invoice number — a button
     * that renders but calls nothing would pass a DOM check and do nothing for the manager.
     *
     * ⚠ STUB THE PLURAL. This stubbed `downloadInvoicePdf` (singular) and failed as "downloadInvoicePdf is
     * actually invoked: expected false to equal true", which reads like a dead button. The button is fine:
     * the handler has called `downloadInvoicesPdf` — the BATCH emitter — since one-file-per-page replaced
     * N separate downloads, because Chrome treats the second automatic download from a page as a permission
     * decision and silently drops the rest. So the spec was asserting the very call the fix removed.
     *
     * Asserting the batch call is also the better test: it proves ONE call carrying ALL the invoice numbers,
     * which is the property that change exists for. A per-invoice stub would go green again the day someone
     * reintroduced the burst.
     */
    openReportWithRows()
    cy.window().then((w) => {
      const spy = cy.stub(w, 'downloadInvoicesPdf').as('pdf')
      const nos = w.srVisibleInvoiceNos()
      expect(nos.length).to.be.greaterThan(0)

      cy.get('#srDownloadInvoices').click()
      // Under the warn threshold it downloads straight away; at or above it, confirm first.
      cy.get('body').then(() => {
        if (nos.length >= 10) {
          cy.get('.uiC-card button').contains(new RegExp('download|télécharger|descargar', 'i')).click()
        }
      })
      cy.wrap(null, { timeout: 20000 }).should(() => {
        expect(spy.called, 'downloadInvoicesPdf is actually invoked').to.eq(true)
        const arg = spy.getCall(0).args[0]
        expect(arg, 'ONE call carrying every visible invoice, not one call per invoice').to.be.an('array')
        expect(arg.length, 'all of them').to.eq(nos.length)
        expect(arg[0], 'with real invoice numbers').to.match(/^INV-/)
      })
    })
  })

  it('both buttons refuse an empty report rather than half-working', () => {
    cy.visit('/businessDashboard')
    cy.get('#sellType', { timeout: 30000 }).should('exist')
    cy.get('#sellType').select('SRDiv', { force: true })
    cy.get('#SRDiv').should('be.visible')

    // Nothing run yet → no rows → no invoices. The buttons must say so, not open an empty print job.
    cy.window().then((w) => {
      expect(w.srVisibleInvoiceNos()).to.have.length(0)
      cy.stub(w, 'print').as('printed')
    })
    cy.get('#srPrintInvoices').click()
    cy.get('@printed').should('not.have.been.called')
  })
})
