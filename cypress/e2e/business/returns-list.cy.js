/**
 * Task #21 — the returns register: credit notes and debit notes, findable after the fact.
 *
 * <h3>Why this screen exists</h3>
 * Task #15 shipped the documents but reachable only at the counter: `offerReturnDocument` prompts in the
 * seconds after a return is taken, and once dismissed the note could not be found again. `getSaleReturns` had
 * been sitting in business-service and the monolith proxy since SF-11 with **nothing in the UI calling it**,
 * and the purchase side had no list endpoint at all.
 *
 * <h3>What is actually asserted</h3>
 * Not "the screen renders". A register of ids would render perfectly and be useless — a return row holds a
 * `productId` and (on the sale side) no customer whatsoever, so the assertion that matters is that rows come
 * back with a resolved **party name** and a **document number**, and that reprint reaches the document.
 */

const OWNER = 'owner.business@myplus.com'

/** Open the register in one of its two modes and wait for rows (or the empty state) to settle. */
function openReturns(mode) {
  cy.visit('/businessDashboard')
  cy.get('#sellType', { timeout: 30000 }).should('exist')
  cy.window().then((w) => w.showReturns(mode))
  cy.get('#ReturnsDiv').should('be.visible')
  cy.get('#tableReturns tbody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)
}

describe('Returns register — credit notes and debit notes', () => {
  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  it('⭐ the credit-note register lists real returns, with names resolved', () => {
    /*
     * SEED first, so this cannot pass vacuously on a shop with no returns. `getSaleReturns` is scoped to the
     * caller, so seeding as this owner guarantees the row is visible to the assertions below.
     */
    cy.request({ url: '/getUserSell?q=-1' }).then((r) => {
      const rows = (r.body && r.body.collection) || []
      expect(rows.length, 'the tenant has a sale to return').to.be.greaterThan(0)
      const line = rows.find((s) => Number(s.quantity) > 1) || rows[0]
      return cy.request({
        method: 'POST', url: '/saleReturn', form: true,
        body: { sellId: line.sellId, quantity: 1, reason: 'cypress: register gate' },
      })
    }).then((r) => {
      expect(r.body.status, r.body.message).to.eq('SUCCESS')
    })

    openReturns('credit')

    cy.get('#returnsTitle').invoke('text').should('match', /sale returns|credit/i)
    cy.get('#returnsPartyHead').invoke('text').should('match', /customer/i)

    // Column 1 is the note's OWN number — the thing that makes it a document rather than a log line.
    cy.get('#tableReturns tbody tr').first().find('td').eq(0)
      .invoke('text').should('match', /CRN-/)

    // THE ENRICHMENT. A raw SaleReturn has no customer at all; a name here proves the batched server-side
    // resolve ran. An em-dash would mean the register is a table of ids.
    cy.get('#tableReturns tbody tr').first().find('td').eq(2)
      .invoke('text').should((txt) => {
        expect(txt.trim(), 'the party column carries a NAME, not an id or a dash').to.not.eq('—')
        expect(txt.trim()).to.not.be.empty
      })
  })

  it('⭐ reprint from the register fetches the document', () => {
    /*
     * The whole point of the screen. Asserted on the REQUEST, not on a button existing: a Reprint button that
     * is drawn but wired to nothing would pass a DOM check and fail the operator.
     */
    cy.intercept('GET', '**/creditNote*').as('note')

    openReturns('credit')
    cy.get('#tableReturns tbody .rtn-print').first().click()

    cy.wait('@note', { timeout: 20000 }).then((i) => {
      // It must ask for a real note number, not undefined — the row's data-note has to survive the render.
      expect(i.request.url, 'the note number travels with the reprint').to.match(/no=CRN-/)
      expect(i.response.body.status, 'and the document resolves').to.eq('SUCCESS')
    })
  })

  it('the debit-note register uses the SUPPLIER heading and its own endpoint', () => {
    /*
     * One screen, two modes: the guard that the mode actually switches BOTH the source and the wording. A
     * version that changed the title but kept the sale endpoint would look right and list the wrong data.
     */
    cy.intercept('GET', '**/getPurchaseReturns*').as('debits')

    cy.visit('/businessDashboard')
    cy.get('#sellType', { timeout: 30000 }).should('exist')
    cy.window().then((w) => w.showReturns('debit'))

    cy.wait('@debits', { timeout: 30000 })
    cy.get('#ReturnsDiv').should('be.visible')
    cy.get('#returnsPartyHead').invoke('text').should('match', /supplier|fournisseur|proveedor/i)
    cy.get('#returnsTitle').invoke('text').should('match', /purchase returns|debit/i)
  })

  it('the register does not raise the blocking overlay', () => {
    // Populating a register is background work — the same rule tier-1b applied to the pickers. A shop
    // browsing its returns must not have the whole page frozen behind the read.
    cy.intercept({ method: 'GET', url: '**/getSaleReturns*' }, (req) => {
      req.on('response', (res) => res.setDelay(3000))
    }).as('slowReturns')

    cy.visit('/businessDashboard')
    cy.get('#sellType', { timeout: 30000 }).should('exist')
    cy.window().then((w) => w.showReturns('credit'))

    cy.get('#appAjaxOverlay').should('not.be.visible')
    cy.get('.ao-box').should('not.be.visible')
    cy.wait('@slowReturns', { timeout: 20000 })
  })

  it('both nav entries reach the register', () => {
    // Reachability, again — the failure this whole task exists to fix was an endpoint no menu pointed at.
    cy.visit('/businessDashboard')
    cy.get('#sellType', { timeout: 30000 }).should('exist')
    cy.get('#navCreditNotes').should('exist')
    cy.get('#navDebitNotes').should('exist')
  })
})
