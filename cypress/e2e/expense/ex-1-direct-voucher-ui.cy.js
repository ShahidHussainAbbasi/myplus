/**
 * EX-1 — the Expenses screen, driven through the REAL UI (Till → Expenses) on the business dashboard.
 *
 * Design: microservices/docs/slices/ex-1-direct-expense-voucher.md §4. Money is proven in the API gate
 * (ex-1-direct-voucher-api.cy.js, trial balance); this one proves a person can reach it, use it, and is never
 * told "in the books" before the ledger says so.
 *
 * <h3>Fresh login after switching the module on</h3>
 * The monolith calls services with the JWT minted at login, and that token carries the capabilities of that
 * moment. So the capability is switched first and a NEW session (distinct cache key) is taken afterwards —
 * otherwise expense-service correctly answers "not switched on" and the case tests the fixture, not the feature.
 */

const CAP = 'expenseManagement'
const NAV = '#navExpenses'

const openTillMenu = () =>
  cy.get('#snavTill').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavTill .snav-btn').click() })

const freshOwner = () => cy.loginAsOwner(undefined, undefined, 'ex1-' + Date.now())

const openExpenses = () => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  openTillMenu()
  cy.get(NAV).should('be.visible').click()
  cy.get('#ExpenseDiv').should('be.visible')
  cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
}

/** bootstrap-select draws a button over the native list; set the NATIVE value and let it re-render. */
const pickCategory = (name) =>
  cy.get('#expCategory').then(($s) => {
    const opt = [...$s[0].options].find((o) => o.text === name)
    expect(opt, `category "${name}" offered`).to.exist
    cy.wrap($s).select(opt.value, { force: true })
  })

describe('EX-1 — Expenses screen (business dashboard)', () => {
  after(() => {
    cy.loginAsOwner()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } })
  })

  it('1 — OFF: Till has no Expenses entry', () => {
    cy.loginAsOwner()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } })
    freshOwner()
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    openTillMenu()
    cy.get(NAV).closest('li').should('have.class', 'cap-off')
    cy.get(NAV).should('not.be.visible')
  })

  describe('ON', () => {
    before(() => {
      cy.loginAsOwner()
      cy.setCapability(CAP, true)
    })

    it('2 — record rent paid in cash; the row shows Posting… and then In the books', () => {
      const payee = 'EX1 landlord ' + Date.now()
      freshOwner()
      openExpenses()
      pickCategory('Rent')
      cy.get('#expAmount').type('1500')
      cy.get('#expPaidFrom').select('CASH', { force: true })
      cy.get('#expPayee').type(payee)
      cy.get('[data-cy=save-expense]').click()
      cy.get('#expMsg').should('contain', 'Posting to the books')
      cy.contains('#tableExpense tbody tr', payee).within(() => {
        cy.contains(/^EXP-\d{6}$/).should('exist')
        cy.contains('1,500.00')
        cy.contains('Rent')
        // never "In the books" on the client's word: whatever it starts as, it must END as In the books
        cy.get('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
      })
    })

    it('3 — a double click on Save makes ONE expense', () => {
      const payee = 'EX1 double ' + Date.now()
      freshOwner()
      openExpenses()
      pickCategory('Fuel and transport')
      cy.get('#expAmount').type('77')
      cy.get('#expPayee').type(payee)
      cy.get('[data-cy=save-expense]').dblclick()
      cy.get('#expMsg').should('contain', 'Posting to the books')
      cy.contains('#tableExpense tbody tr', payee).should('have.length', 1)
      cy.request('/expense/vouchers?size=200').then((r) => {
        const mine = r.body.data.content.filter((v) => v.payeeName === payee)
        expect(mine, 'the server holds exactly one').to.have.length(1)
      })
    })

    it('4 — void with a reason, through the dialog', () => {
      const payee = 'EX1 void ' + Date.now()
      freshOwner()
      openExpenses()
      pickCategory('Repairs and maintenance')
      cy.get('#expAmount').type('250')
      cy.get('#expPayee').type(payee)
      cy.get('[data-cy=save-expense]').click()
      cy.contains('#tableExpense tbody tr', payee).find('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
      cy.contains('#tableExpense tbody tr', payee).find('[data-cy=void-expense]').click()
      cy.get('.uiC-input').type('entered by mistake')
      cy.get('[data-ui-confirm="ok"]').click()
      cy.contains('#tableExpense tbody tr', payee).find('.exp-chip').should('contain', 'Void')
      cy.contains('#tableExpense tbody tr', payee).find('[data-cy=void-expense]').should('not.exist')
    })
  })
})
