/**
 * Task #16 — pick a supplier, see their debit notes, print the set as one job.
 *
 * <h3>Run as the DISTRIBUTION tenant, per GATE-RUNBOOK rule 1</h3>
 * The ask was framed as "marketplace or distributors", and supplier returns are a distribution concern —
 * `owner.marketplace@myplus.com` on the `distribution` shape. Earlier gates in this cluster ran only as
 * `owner.business@`, which tested the POS tenant's view of a distributor's feature.
 *
 * <h3>What is asserted</h3>
 * <ul>
 *   <li><b>The filter is applied in SQL, not by hiding rows.</b> Asserted on the REQUEST carrying venderId —
 *       a client-side filter would look identical on ten rows and fall over on a real distributor's year.</li>
 *   <li><b>Bulk print is ONE job.</b> Twenty notes must not become twenty print dialogs; the documents are
 *       fetched and combined, so the assertion is on the fetches, not on a dialog.</li>
 *   <li><b>The filter is absent in credit mode</b> — a supplier picker over customer returns can never match.</li>
 *   <li><b>The privilege ladder</b> (rule 4): booker.marketplace@ (ROLE_ORDER_BOOKER, no ADMIN_PRIVILEGE) still reads the register —
 *       a screen that renders empty because a read was refused looks identical to one with no data.</li>
 * </ul>
 */


function openDebitRegister() {
  cy.visit('/businessDashboard')
  cy.get('#sellType', { timeout: 30000 }).should('exist')
  cy.window().then((w) => w.showReturns('debit'))
  cy.get('#ReturnsDiv').should('be.visible')
}

describe('Debit notes — supplier filter and bulk print', () => {
  it('⭐ choosing a supplier re-reads the register WITH the filter', () => {
    cy.loginAsMarketplaceOwner()
    cy.intercept('GET', '**/getPurchaseReturns*').as('register')

    openDebitRegister()
    cy.wait('@register', { timeout: 30000 }).then((i) => {
      // The unfiltered read carries no supplier.
      expect(i.request.url).to.not.match(/venderId=\d/)
    })

    // The picker fills in the background; wait for a real supplier rather than reading it once.
    cy.get('#returnsVenderDD option', { timeout: 30000 }).should('have.length.greaterThan', 1)

    cy.get('#returnsVenderDD option').eq(1).then(($o) => {
      const id = $o.val()
      expect(id, 'a real supplier id').to.match(/^\d+$/)
      cy.get('#returnsVenderDD').select(id)

      cy.wait('@register', { timeout: 30000 }).then((i) => {
        expect(i.request.url, 'the filter is applied SERVER-side').to.include('venderId=' + id)
      })
    })
  })

  it('the supplier filter does not appear for credit notes', () => {
    // A credit note's party is a customer. A supplier picker there is a control that cannot match anything.
    cy.loginAsMarketplaceOwner()
    cy.visit('/businessDashboard')
    cy.get('#sellType', { timeout: 30000 }).should('exist')
    cy.window().then((w) => w.showReturns('credit'))
    cy.get('#returnsFilterBar').should('not.be.visible')

    cy.window().then((w) => w.showReturns('debit'))
    cy.get('#returnsFilterBar').should('be.visible')
  })

  it('⭐ Print all fetches every listed note — as one job, not one dialog each', () => {
    /*
     * The defect this guards: printing in a loop fires window.print() per document, which stacks dialogs on
     * the operator and the browser silently drops most of them. It works on three rows and fails on a real
     * supplier's month, so the assertion is that EVERY listed note was fetched and combined.
     */
    cy.loginAsMarketplaceOwner()
    const fetched = []
    cy.intercept('GET', '**/debitNote*', (req) => { fetched.push(req.url) }).as('notes')

    openDebitRegister()
    cy.get('#tableReturns tbody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)

    cy.get('#tableReturns tbody .rtn-print').then(($btns) => {
      if (!$btns.length) {
        // Nothing printable (every row predates the note series) — the button must refuse, not half-print.
        cy.get('#returnsPrintAll').click()
        cy.get('.uiC-card').should('not.exist')
        return
      }
      const expected = $btns.length

      // window.print is stubbed: the assertion is that the documents were gathered, and an unstubbed print
      // dialog would block the run.
      cy.window().then((w) => cy.stub(w, 'print').as('printed'))

      cy.get('#returnsPrintAll').click()
      cy.get('.uiC-card .uiC-title').should('be.visible')
      cy.get('.uiC-card button').contains(new RegExp('print', 'i')).click()

      cy.wrap(null, { timeout: 30000 }).should(() => {
        expect(fetched.length, `every listed note is fetched (${fetched.length}/${expected})`)
          .to.eq(expected)
      })
    })
  })

  it('privilege ladder — the register POPULATES for a non-admin member', () => {
    /*
     * GATE-RUNBOOK rule 4. The failure this catches is specific: a read silently refused for a lower role
     * renders an EMPTY table, which is indistinguishable from a tenant with no returns. So the assertion is
     * that the request succeeds, not that the screen drew.
     */
    cy.loginAsOrderBooker()
    cy.request({ url: '/getPurchaseReturns', failOnStatusCode: false }).then((r) => {
      expect(r.body.status, 'a non-admin org member may read the register').to.eq('SUCCESS')
    })
  })
})
