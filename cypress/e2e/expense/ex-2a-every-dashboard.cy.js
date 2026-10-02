/**
 * EX-2a — Expense management on the school, welfare and farm dashboards.
 *
 * Design: microservices/docs/slices/ex-2a-expenses-on-every-dashboard.md §4.
 *
 * <h3>Gate per DOMAIN, each with its own tenant</h3>
 * owner.education, owner.welfare and owner.agriculture are three separate organisations, so a pass on one
 * says nothing about another — and a switch that silently wrote to the wrong org would fail the next domain.
 *
 * <h3>Fresh session after the switch</h3>
 * The session token carries the capabilities of the moment it was minted. The card re-mints it on save, and the
 * spec still signs in again with a new cache key before using the module, so the case cannot pass on a stale
 * session that happened to be right.
 */

const PW = 'Demo@2025!'
const KEY = 'org.cap.expenseManagement'

const DOMAINS = [
  {
    name: 'school', email: 'owner.education@myplus.com', dash: '/educationDashboard', check: '/getDashboardData',
    openConfig: () => {
      cy.get('#snavFee').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavFee .snav-btn').click() })
      cy.contains('#snavFee a', 'Configuration').click()
    },
    openExpenses: () => {
      cy.get('#snavFee').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavFee .snav-btn').click() })
      cy.get('#navExpenses').should('be.visible').click()
    },
  },
  {
    name: 'welfare', email: 'owner.welfare@myplus.com', dash: '/welfareDashboard', check: '/getWelfareConfig',
    openConfig: () => cy.contains('.app-sidebar a.sb-link', 'Configuration').click({ force: true }),
    openExpenses: () => cy.get('#navExpenses').should('exist').click({ force: true }),
  },
  {
    name: 'farm', email: 'owner.agriculture@myplus.com', dash: '/agricultureDashboard', check: '/agricultureDashboard',
    openConfig: () => cy.contains('.app-sidebar a.sb-link', 'Configuration').click({ force: true }),
    openExpenses: () => cy.get('#navExpenses').should('exist').click({ force: true }),
  },
]

/** Same validate paths as the per-module owner commands (commands.js) — loginAs needs one to check the session. */
const signIn = (d, fresh) => cy.loginAs(d.email, PW, d.check, fresh ? 'ex2a-' + Date.now() : undefined)
const visit = (dash) => { cy.visit(dash); cy.waitForAppReady() }
const resetSwitch = () =>
  cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: KEY }, failOnStatusCode: false })

describe('EX-2a — Expenses on every dashboard', () => {
  after(() => {
    DOMAINS.forEach((d) => { signIn(d); resetSwitch() })
  })

  DOMAINS.forEach((d) => {
    describe(`${d.name} (${d.email})`, () => {
      it('1 — Configuration offers the Modules card with Expense management OFF; no Expenses entry', () => {
        signIn(d)
        resetSwitch()
        signIn(d, true)
        visit(d.dash)
        cy.get('#navExpenses').closest('[data-capability]').should('have.class', 'cap-off')
        d.openConfig()
        cy.get(`#moduleSwitches [data-key="${KEY}"]`, { timeout: 20000 }).should('exist').and('not.be.checked')
        cy.get('#moduleSwitches').should('contain', 'Expense management')
      })

      it('2 — switching it on saves for THIS business and the Expenses entry appears', () => {
        signIn(d, true)
        visit(d.dash)
        d.openConfig()
        cy.get(`#moduleSwitches [data-key="${KEY}"]`, { timeout: 20000 }).check()
        cy.get('#moduleSwitchesMsg').should('contain', 'Saved')
        cy.request('/getCapabilities').its('body.data.expenseManagement').should('eq', true)
        signIn(d, true)
        visit(d.dash)
        cy.get('#navExpenses').closest('[data-capability]').should('not.have.class', 'cap-off')
      })

      it('3 — record rent through the screen; it reaches the books', () => {
        const payee = `EX2a ${d.name} ` + Date.now()
        signIn(d, true)
        visit(d.dash)
        d.openExpenses()
        cy.get('#ExpenseDiv').should('be.visible')
        cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
        cy.get('#expCategory').then(($s) => {
          const opt = [...$s[0].options].find((o) => o.text === 'Rent')
          expect(opt, 'Rent offered').to.exist
          cy.wrap($s).select(opt.value, { force: true })
        })
        cy.get('#expAmount').type('900')
        cy.get('#expPayee').type(payee)
        cy.get('[data-cy=save-expense]').click()
        cy.get('#expMsg').should('contain', 'Posting to the books')
        cy.contains('#tableExpense tbody tr', payee).within(() => {
          cy.contains(/^EXP-\d{6}$/).should('exist')
          cy.get('.exp-chip', { timeout: 25000 }).should('contain', 'In the books')
        })
      })

      it('4 — the card cannot flip a trade capability (allow-list)', () => {
        signIn(d, true)
        cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, failOnStatusCode: false,
          body: { key: 'org.cap.installments', enabled: 'false' } })
          .then((r) => {
            expect(r.body.success, JSON.stringify(r.body)).to.eq(false)
            expect(r.body.message).to.match(/not a module/i)
          })
      })
    })
  })

  it('5 — a user-tier member cannot switch modules', () => {
    signIn({ email: 'user.education@myplus.com', check: '/educationDashboard' }, true)
    cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, failOnStatusCode: false,
      body: { key: KEY, enabled: 'true' } })
      .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(false))
  })
})
