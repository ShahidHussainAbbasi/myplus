/**
 * TEAM-1 — after adding a member: who has not set a password yet, resend the invitation, remove (reversible) and
 * reactivate (client, 2026-10-13: the open Team items F4/F5; Slack pending invitations, Shopify "the older link no
 * longer works", Odoo archive). Every step on the real Team screen.
 *
 *   TM-01  a new member shows "Invited — no password yet" with Resend invitation
 *   TM-02  Resend sends a fresh e-mail and the OLDER link stops working; the new one sets the password
 *   TM-03  once the password is set the row says Active and offers no Resend
 *   TM-04  Remove asks why (empty is refused); the row says Removed; the member cannot sign in, is told their
 *          access was removed (not "Invalid Credentials"), and is NOT handed an empty business of their own
 *   TM-05  Reactivate: Active again, and the member signs in with their own password
 *
 * Tenant: owner.pharma@. Each run adds ONE member: tm.<run>@myplus.test (.test never delivers); it is left REMOVED at
 * the end (removal is the clean-up now — nothing is ever deleted).
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/auth/team-members-manage.cy.js"
 */
import { HMS } from '../../support/hms-accounts'

describe('TEAM-1 — manage a team member after adding them', () => {
  const run = String(Date.now()).slice(-7)
  const EMAIL = `tm.${run}@myplus.test`
  const PW = 'Kiwi#Lamp58'                   // fixed, no sequences (the password rules refuse runs like 123)
  const MAILPIT = 'http://localhost:8025'
  const S = {}
  const shot = (n) => cy.screenshot(`team-manage/${n}`, { capture: 'viewport', overwrite: true })
  const owner = () => cy.loginAs(HMS.OWNER, HMS.PW, '/getBusinessDashboardStats', `tm-${run}-owner`)
  const openTeam = () => {
    cy.visit('/businessDashboard')
    cy.window().then((w) => w.showTeam())
    cy.get('#TeamDiv').should('be.visible')
  }
  const row = () => cy.contains('#tableTeam tbody tr', EMAIL, { timeout: 15000 })
  const tokensTo = (since) => cy.request(`${MAILPIT}/api/v1/messages?limit=100`).then((r) => {
    const ms = (r.body.messages || []).filter((x) => (x.To || []).some((a) => a.Address === EMAIL) && (!since || x.Created > since))
    return ms
  })
  const newestToken = (since) => {
    const look = (tries) => tokensTo(since).then((ms) => {
      if (ms.length) return cy.request(`${MAILPIT}/api/v1/message/${ms[0].ID}`).its('body')
        .then((m) => /token=([A-Za-z0-9-]+)/.exec(m.Text || m.HTML || '')[1])
      if (tries <= 0) throw new Error('no e-mail to ' + EMAIL)
      return cy.wait(1000).then(() => look(tries - 1))
    })
    return look(20)
  }
  const signIn = (email, password) => {
    cy.clearCookies()
    cy.visit('/login')
    cy.get('input[name="username"]').type(email)
    cy.get('input[name="password"]').type(password, { log: false })
    cy.get('#loginSubmit').click()
  }

  it('TM-01 a new member shows "Invited — no password yet" with Resend invitation', () => {
    owner()
    openTeam()
    cy.get('#teamFirstName').type('Manage')
    cy.get('#teamLastName').type(`Test ${run}`)
    cy.get('#teamEmail').type(EMAIL)
    cy.get('#teamRole').select('USER', { force: true })
    cy.intercept('POST', '**/team/users').as('add')
    cy.get('#addTeamUser').click()
    cy.wait('@add').its('response.statusCode').should('eq', 200)
    row().should('contain.text', 'Invited').find('.js-team-resend').should('be.visible')
    shot('TM-01-1-invited')
    newestToken().then((t) => { S.first = t })
  })

  it('TM-02 Resend sends a fresh link; the older one stops working; the new one sets the password', () => {
    owner()
    openTeam()
    const since = new Date().toISOString()
    row().find('.js-team-resend').click()
    cy.get('#teamMsg').should('be.visible').and('contain.text', 'Older links no longer work')
    newestToken(since).then((t) => {
      expect(t, 'a NEW link').to.not.eq(S.first)
      S.second = t
    })
    // the older link is refused …
    cy.then(() => {
      cy.clearCookies()
      cy.visit(`/user/changePassword?token=${S.first}`)
      cy.get('#password').type(PW, { log: false })
      cy.get('#matchPassword').type(PW, { log: false })
      cy.contains('button', /update|save|submit/i).click()
      cy.get('#globalError', { timeout: 15000 }).should('be.visible')
      shot('TM-02-1-old-link-refused')
    })
    // … and the new one works
    cy.then(() => {
      cy.visit(`/user/changePassword?token=${S.second}`)
      cy.get('#password').type(PW, { log: false })
      cy.get('#matchPassword').type(PW, { log: false })
      cy.contains('button', /update|save|submit/i).click()
      cy.location('pathname', { timeout: 15000 }).should('include', '/login')
    })
  })

  it('TM-03 once the password is set the row says Active and offers no Resend', () => {
    owner()
    openTeam()
    row().should('contain.text', 'Active').find('.js-team-resend').should('not.exist')
  })

  it('TM-04 Remove asks why; the row says Removed; the member cannot sign in and gets no business of their own', () => {
    owner()
    openTeam()
    row().find('.js-team-remove').click()
    cy.get('.uiC-input').should('be.visible')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('.uiC-card').should('contain.text', 'Say why')          // required
    shot('TM-04-1-remove-asks-why')
    cy.get('.uiC-input').type('Left the clinic (test)')
    cy.get('[data-ui-confirm="ok"]').click()
    row().should('contain.text', 'Removed').find('.js-team-reactivate').should('be.visible')
    shot('TM-04-2-removed')
    // the removed member's sign-in is refused. Before TEAM-1 this SUCCEEDED: getOrCreatePrimaryOrg made them a new
    // empty business of their own — so staying on /login is also the proof that none was made
    signIn(EMAIL, PW)
    cy.location('pathname', { timeout: 15000 }).should('include', '/login')
    // … and told WHY — "Invalid Credentials" sent them to reset a password that was never the problem
    cy.get('.msg-bar.error').should('be.visible').and('contain.text', 'access to this business was removed')
    shot('TM-04-3-removed-cannot-sign-in')
    // the owner's list still holds them, as Removed (nothing deleted)
    owner()
    cy.request('/team/users').its('body').then((b) => {
      const m = (b.data || b.object || []).find((x) => x.email === EMAIL)
      expect(m && m.status, 'still REMOVED in this business').to.eq('INACTIVE')
    })
  })

  it('TM-05 Reactivate: Active again, and the member signs in with their own password', () => {
    owner()
    openTeam()
    row().find('.js-team-reactivate').click()
    cy.get('[data-ui-confirm="ok"]').click()
    row().should('contain.text', 'Active')
    signIn(EMAIL, PW)
    cy.location('pathname', { timeout: 20000 }).should('not.include', '/login')
    shot('TM-05-1-reactivated-signed-in')
  })

  after(() => {
    // clean-up = REMOVE (nothing is ever deleted); the address is unique and undeliverable
    owner()
    cy.request('/team/users').its('body').then((b) => {
      const m = (b.data || b.object || []).find((x) => x.email === EMAIL)
      if (m && m.status !== 'INACTIVE') {
        cy.postJson(`/team/users/${m.userId}/remove`, { reason: 'cypress cleanup' })
      }
    })
  })
})
