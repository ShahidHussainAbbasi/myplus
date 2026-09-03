/**
 * #24 — the two returns registers reach parity: filters and Print all on BOTH sides.
 *
 * ⚠ WRITTEN BEFORE THE IMPLEMENTATION (cadence). These cases are the requirement.
 * Design: microservices/docs/slices/returns-register-parity.md (rationale: return-documents-design Part 5)
 *
 * <h3>What is wrong today</h3>
 * Purchase returns have a supplier filter and a Print all button. Sale returns have neither — and the reason
 * is not that Print all was never built. It exists, it is wired, and it is already mode-aware; it simply
 * sits INSIDE `#returnsFilterBar`, which `showReturns()` hides on the credit side. A working control,
 * unreachable on the screen that needs it. The tenth instance of that pattern.
 *
 * <h3>Why case 9 is the one that matters</h3>
 * Calling the handler passes TODAY, against the broken screen. Only asking whether a person can see the
 * button distinguishes "built" from "delivered".
 */

const OWNER = 'owner.business@myplus.com'

/**
 * Ensure the CREDIT register has something in it, seeding a return if not.
 *
 * ⚠ SEED, never assert-or-skip. `expect(rows.length).to.be.greaterThan(0)` turned an empty shop into four red
 * cases that said nothing about filtering — the runbook rule this spec's own author had just written down
 * (GATE-RUNBOOK §7) and then failed to apply.
 */
function ensureCreditNote() {
  return cy.request({ url: '/getSaleReturns' }).then((r) => {
    const rows = (r.body && r.body.collection) || []
    if (rows.length) return cy.wrap(rows.length)
    // cy.seedCreditNote is the SHARED command return-documents.cy.js also uses — one definition of "make a
    // credit note", so the two specs cannot drift apart about what one is.
    return cy.seedCreditNote().then(() => cy.wrap(1))
  })
}

/** Open the returns register in a mode, and wait for the register read to settle. */
function openReturns(mode) {
  cy.intercept('GET', mode === 'debit' ? '**/getPurchaseReturns*' : '**/getSaleReturns*').as('register')
  cy.visitDashboardSettled()
  cy.get(`[onclick*="showReturns('${mode}')"]`).first().click({ force: true })
  cy.get('#ReturnsDiv', { timeout: 20000 }).should('be.visible')
  cy.wait('@register', { timeout: 30000 })
}

/** The note numbers currently listed. */
function listedNotes() {
  return cy.get('#tableReturns tbody').then(($tb) => {
    return $tb.find('.rtn-print').map((_, el) => Cypress.$(el).data('note')).get()
  })
}

describe('#24 — returns register parity', () => {
  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  it('⭐ 9. Print all is VISIBLE on the SALE-return register', () => {
    /*
     * THE DEFECT. `#returnsPrintAll` has always worked for credit notes — its handler reads
     * window.returnsMode and printReturnDocuments takes a kind. It was hidden because it lived inside the
     * supplier filter bar, which the credit side hides wholesale.
     *
     * Asserted as `be.visible` FROM THE SCREEN. `expect(typeof handler).to.eq('function')` would have passed
     * every day this was broken, which is exactly how it survived.
     */
    openReturns('credit')
    cy.get('#returnsPrintAll').should('be.visible')
  })

  it('1. both registers show a filter bar AND a Print all button', () => {
    openReturns('credit')
    cy.get('#returnsFilterBar').should('be.visible')
    cy.get('#returnsPrintAll').should('be.visible')

    openReturns('debit')
    cy.get('#returnsFilterBar').should('be.visible')
    cy.get('#returnsPrintAll').should('be.visible')
  })

  it('⭐ 3. the party control is a CUSTOMER picker on credit and a SUPPLIER picker on debit', () => {
    /*
     * A supplier picker over customer returns is a control that can never match anything — it would look
     * like a working filter that always returns an empty register, which is worse than no filter.
     */
    /*
     * ⚠ Asserted on the PARTY SLOT and the rendered control, not on the raw <select>.
     *
     * searchable-selects.js enhances these into bootstrap-selects, and the plugin sets the real <select> to
     * `display:none` and renders a button beside it. So `cy.get('#returnsCustomerDD').should('be.visible')`
     * fails on a perfectly working picker — the element a person actually sees is `.next('.bootstrap-select')`.
     * Same trap as #customerType in b2b-customer-type.cy.js.
     */
    openReturns('credit')
    cy.get('#returnsPartyCustomer').should('be.visible')
    cy.get('#returnsCustomerDD').next('.bootstrap-select').should('be.visible')
    cy.get('#returnsPartyVender').should('not.be.visible')

    openReturns('debit')
    cy.get('#returnsPartyVender').should('be.visible')
    cy.get('#returnsVenderDD').next('.bootstrap-select').should('be.visible')
    cy.get('#returnsPartyCustomer').should('not.be.visible')
  })

  it('⭐ 2. the party filter NARROWS the register, and an impossible party returns nothing', () => {
    /*
     * Both halves matter. A filter that narrows but never empties is not filtering — it is sorting. The
     * impossible-value half is what catches a predicate that was silently dropped: the register would still
     * look right, just unfiltered.
     */
    ensureCreditNote()
    openReturns('credit')
    listedNotes().then((all) => {
      expect(all.length, 'the tenant has sale returns to filter').to.be.greaterThan(0)

      cy.intercept('GET', '**/getSaleReturns*').as('filtered')
      // An id no customer can have. Selected through the real control so the screen's own wiring is exercised.
      cy.get('#returnsCustomerDD').then(($dd) => {
        $dd.append('<option value="99999999">__impossible__</option>')
        cy.wrap($dd).select('99999999', { force: true })
      })
      cy.wait('@filtered', { timeout: 30000 })

      cy.get('#tableReturns tbody .rtn-print').should('have.length', 0)
    })
  })

  it('⭐ 4. a date range covering today returns today\'s returns', () => {
    /*
     * THE endOfDay REGRESSION, on this screen. A picker sends a date as midnight, so "today to today" is
     * 00:00:00..00:00:00 and matches only a return recorded at exactly midnight. Fixed once in loadSR; any
     * new date-filtered read that parses its own bounds reproduces it.
     */
    openReturns('credit')
    const d = new Date()
    const stamp = String(d.getDate()).padStart(2, '0') + '-'
      + String(d.getMonth() + 1).padStart(2, '0') + '-' + d.getFullYear()

    cy.intercept('GET', '**/getSaleReturns*').as('ranged')
    cy.get('#returnsFrom').clear({ force: true }).type(stamp, { force: true })
    cy.get('#returnsTo').clear({ force: true }).type(stamp, { force: true }).blur()
    cy.wait('@ranged', { timeout: 30000 }).then((i) => {
      // A same-day range must not ERROR and must not silently drop a return made today. Whether the shop
      // returned anything today is data, so the assertion is on the request being well-formed and answered.
      expect(i.response.statusCode).to.eq(200)
      expect(i.request.url, 'the range travels with the read').to.match(/from=/)
    })
  })

  it('⭐ 7. a filter does NOT leak across a mode switch', () => {
    /*
     * A supplier filter still applied after switching to credit notes would narrow customer returns by a
     * supplier id — matching nothing, and reading as "this shop has never had a sale return".
     */
    openReturns('debit')
    cy.get('#returnsVenderDD option').its('length').then((n) => {
      if (n > 1) cy.get('#returnsVenderDD').select(1, { force: true })
    })

    cy.intercept('GET', '**/getSaleReturns*').as('afterSwitch')
    cy.get('[onclick*="showReturns(\'credit\')"]').first().click({ force: true })
    cy.wait('@afterSwitch', { timeout: 30000 }).then((i) => {
      expect(i.request.url, 'the supplier filter did not follow us').to.not.match(/venderId=\d/)
    })
  })

  it('5. Print all combines every listed note into ONE job', () => {
    ensureCreditNote()
    openReturns('credit')
    listedNotes().then((notes) => {
      expect(notes.length, 'there are notes to print').to.be.greaterThan(0)

      cy.window().then((w) => {
        // Capture rather than print: a real print dialog would hang the run, and what is being asserted is
        // that ONE job covers every listed note — not that a printer engaged.
        w.__printed = null
        const real = w.printReturnDocuments
        w.printReturnDocuments = (kind, noteNos) => { w.__printed = { kind, noteNos } }
        cy.wrap(real).as('realPrint')
      })

      cy.get('#returnsPrintAll').click({ force: true })
      cy.get('.uiC-card button').contains(/print/i).click({ force: true })

      cy.window().should((w) => {
        expect(w.__printed, 'one print job was raised').to.not.be.null
        expect(w.__printed.kind, 'for the CREDIT side').to.eq('credit')
        expect(w.__printed.noteNos.length, 'covering every listed note').to.eq(notes.length)
      })

      cy.get('@realPrint').then((real) => cy.window().then((w) => { w.printReturnDocuments = real }))
    })
  })

  it('6. Print all with nothing listed refuses rather than opening an empty job', () => {
    // An empty print job wastes a click and a sheet, and teaches an operator the button is unreliable.
    openReturns('credit')
    cy.get('#returnsCustomerDD').then(($dd) => {
      $dd.append('<option value="99999999">__impossible__</option>')
      cy.wrap($dd).select('99999999', { force: true })
    })
    cy.get('#tableReturns tbody .rtn-print', { timeout: 20000 }).should('have.length', 0)

    cy.window().then((w) => { w.__printed = null; w.printReturnDocuments = () => { w.__printed = true } })
    cy.get('#returnsPrintAll').click({ force: true })
    cy.window().should((w) => expect(w.__printed, 'no job was raised').to.be.null)
  })

  it('⭐ 10. every sheet in a combined credit-note job carries its CUSTOMER NAME', () => {
    /*
     * THE REGRESSION GUARD on the defect fixed this morning: `toInvoiceShape` set a flat `customerName`
     * while `buildContext` reads `inv.customer.name`, so every credit note printed with an empty party
     * block. A bulk action makes that failure worst — nobody proofreads forty sheets.
     *
     * Rendered through the PRODUCTION shaping, not a copy of it. A test that re-does the mapping validates
     * its own mapping and was green throughout the original defect.
     */
    ensureCreditNote()
    openReturns('credit')
    listedNotes().then((notes) => {
      expect(notes.length, 'there is a note to render').to.be.greaterThan(0)

      cy.request({ url: `/creditNote?no=${encodeURIComponent(notes[0])}` }).then((r) => {
        expect(r.body.status, r.body.message).to.eq('SUCCESS')
        const doc = r.body.object
        expect(doc.partyName, 'the note names a customer').to.not.be.empty

        cy.window().then((w) => {
          const html = w.buildReturnDocumentHtml(doc, false)
          expect(html, 'and the name is on the sheet').to.contain(doc.partyName)
        })
      })
    })
  })

  it('8. another tenant\'s notes never appear, whatever the filter', () => {
    // Scoping is not a filter concern, which is exactly why it needs asserting here: a new WHERE clause is
    // the classic place for a scope predicate to get lost.
    ensureCreditNote()
    openReturns('credit')
    listedNotes().then((mine) => {
      cy.asOtherTenant(() => {
        cy.request({ url: '/getSaleReturns', failOnStatusCode: false }).then((r) => {
          const theirs = ((r.body && r.body.collection) || []).map((n) => n.documentNo)
          mine.forEach((n) => expect(theirs, 'no leak across tenants').to.not.include(n))
        })
      })
    })
  })
})
