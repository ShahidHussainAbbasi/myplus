/**
 * LOCK-1 — five wrong passwords lock the account (2026-10-14).
 *
 * Found during TEAM-1: AuthService.login saved the failed-attempt count and then threw, and the throw rolled the save
 * back — the count never left 0, so no account ever locked (proved over HTTP: seven wrong passwords, then the right
 * one passed the lock check). Fix: @Transactional(noRollbackFor = SignInRefusedException), as the marketplace does.
 *
 *   LK-01  wrong passwords 1-5 each say "Invalid Credentials" on the real login page
 *   LK-02  the next attempt says the account is temporarily LOCKED — not "Invalid Credentials"
 *
 * Never a shared demo account: each run adds ONE throwaway member (lk.<run>@myplus.test, .test never delivers) to
 * owner.pharma's team; its password is the random one set on creation, so every attempt here is wrong. The lock
 * lifts by itself after 30 minutes; the member is REMOVED at the end (nothing is ever deleted).
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/auth/login-lockout.cy.js"
 */
import { HMS } from '../../support/hms-accounts'

describe('LOCK-1 — five wrong passwords lock the account', () => {
  const run = String(Date.now()).slice(-7)
  const EMAIL = `lk.${run}@myplus.test`
  const WRONG = 'Wrong#Guess42'
  const owner = () => cy.loginAs(HMS.OWNER, HMS.PW, '/getBusinessDashboardStats', `lk-${run}-owner`)
  const shot = (n) => cy.screenshot(`login-lockout/${n}`, { capture: 'viewport', overwrite: true })
  const signIn = () => {
    cy.clearCookies()
    cy.visit('/login')
    cy.get('input[name="username"]').type(EMAIL)
    cy.get('input[name="password"]').type(WRONG, { log: false })
    cy.get('#loginSubmit').click()
    cy.location('pathname', { timeout: 15000 }).should('include', '/login')
    return cy.get('.msg-bar.error').should('be.visible')
  }

  before(() => {
    owner()
    cy.postJson('/team/users', { firstName: 'Lock', lastName: `Test ${run}`, email: EMAIL, role: 'USER', storeIds: [] })
      .then((r) => expect(r.body && r.body.data && r.body.data.userId, `member added: ${JSON.stringify(r.body).slice(0, 200)}`).to.be.ok)
  })

  it('LK-01 wrong passwords 1-5 each say "Invalid Credentials"', () => {
    for (let i = 1; i <= 5; i++) {
      signIn().and('contain.text', 'Invalid Credentials')
    }
    shot('LK-01-5th-wrong')
  })

  it('LK-02 the next attempt says the account is temporarily locked', () => {
    signIn().and('contain.text', 'temporarily locked').and('not.contain.text', 'Invalid Credentials')
    shot('LK-02-locked')
  })

  after(() => {
    owner()
    cy.request('/team/users').its('body').then((b) => {
      const m = (b.data || []).find((x) => x.email === EMAIL)
      if (m && m.status !== 'INACTIVE') cy.postJson(`/team/users/${m.userId}/remove`, { reason: 'cypress cleanup' })
    })
  })
})
