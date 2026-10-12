/**
 * TEAM INVITE → FIRST LOGIN (2026-10-12) — how a member the owner adds can sign in, end to end on the screens.
 *
 * The design (auth-service createOrgUser, design D5): the owner never sets or sees a password. The new member gets a
 * random password NOBODY knows and a "set your password" e-mail; the link in it is the only way in, which also proves
 * the address is real. In this stack the mail goes to Mailpit (http://localhost:8025) — never to a real inbox.
 *
 *   TI-01  Owner → Team → Add member: listed Active, "set-password e-mail" sent
 *   TI-02  The e-mail is in Mailpit, with the set-password link (token) — and where the link points
 *   TI-03  Before setting a password, the member cannot sign in
 *   TI-04  The link opened on THIS stack → choose a password → sign in on the login page
 *   TI-05  The same link a second time is refused (single use)
 *   TI-06  Login → "Forgot password?" → a fresh e-mail → a new password → sign in (the way back for an expired link)
 *   TI-08  one member per permission set the client used — Doctor, Clinic reception, Standard: the owner puts them
 *          on the set from the Team row, they set their password from the e-mail and sign in; the page they get is
 *          drawn from THEIR permissions (only a set holding "dispense" gets the #rxCanDispense marker)
 *   TI-07  Mailpit's own screen lists the e-mails (LAST: visiting another origin makes Cypress reload the spec, which
 *          recomputes the per-run name — every case after it would look for a different member)
 *
 * Tenant: owner.pharma@ (Team is the owner's). Each run adds FOUR members: ti.<run>@ and ti.<run>.<set>@myplus.test
 * (.test never delivers).
 * Cleanup: there is NO way to remove a team member yet (an open decision) — the member stays on the Team list; the
 * address is unique per run and undeliverable. Nothing else is changed.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/auth/team-invite-first-login.cy.js"
 */
describe('Team invite → first login', () => {
  const run = String(Date.now()).slice(-7)
  const EMAIL = `ti.${run}@myplus.test`
  const PASSWORD_1 = `Clinic@${run}a`
  const PASSWORD_2 = `Clinic@${run}b`
  const MAILPIT = 'http://localhost:8025'
  let token1 = null
  let link1 = null
  const shot = (n) => cy.screenshot(`team-invite/${n}`, { capture: 'viewport', overwrite: true })

  /** The newest "Password Reset" e-mail to `to` in Mailpit, newer than `after` (ISO); retried while it arrives. */
  const mailTo = (to, after) => {
    const look = (tries) => cy.request(`${MAILPIT}/api/v1/messages?limit=100`).then((r) => {
      const m = (r.body.messages || []).find((x) => (x.To || []).some((a) => a.Address === to)
        && (!after || x.Created > after))
      if (m) return cy.request(`${MAILPIT}/api/v1/message/${m.ID}`).its('body')
      if (tries <= 0) throw new Error(`no e-mail to ${to} in Mailpit`)
      return cy.wait(1000).then(() => look(tries - 1))
    })
    return look(20)
  }
  const tokenOf = (msg) => {
    const m = /token=([A-Za-z0-9-]+)/.exec(msg.Text || msg.HTML || '')
    expect(m, 'a set-password token in the e-mail').to.exist
    return m[1]
  }
  const signIn = (email, password) => {
    cy.clearCookies()
    cy.visit('/login')
    cy.get('input[name="username"]').clear().type(email)
    cy.get('input[name="password"]').clear().type(password, { log: false })
    cy.get('#loginSubmit').click()
  }

  // TI-FIX (2026-10-12) — each defect found in the first runs is asserted, so the gate is RED on the old code:
  //   F1  the Update Password button threw (savePass() without an event) — now no tolerance: Cypress fails the case
  //   F2  no "Forgot password?" on the login page — TI-06 starts from the login page's own link
  //   F6  after saving, login showed "null" — TI-04/TI-06 assert the confirmation text instead
  const SAVED = 'Password reset successfully'
  const SENT = /reset password|e-?mail/i

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement('owner.pharma@myplus.com', 'clinic', 'ACTIVE', 'team invite gate')
    cy.loginAs('owner.pharma@myplus.com', 'Demo@2025!', '/getBusinessDashboardStats', 'ti-clinic')
    cy.setCapability('clinic', true)
  })
  after(() => {
    cy.loginAs('owner.pharma@myplus.com', 'Demo@2025!', '/getBusinessDashboardStats', 'ti-clinic')
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  it('TI-01 the owner adds a member on Team; they are listed and the set-password e-mail is sent', () => {
    cy.loginAsPharmaOwner()
    cy.visit('/businessDashboard')
    cy.window().then((w) => w.showTeam())
    cy.get('#TeamDiv').should('be.visible')
    cy.get('#teamFirstName').type('Invite')
    cy.get('#teamLastName').type(`Test ${run}`)
    cy.get('#teamEmail').type(EMAIL)
    cy.get('#teamRole').select('USER', { force: true })
    shot('TI-01-1-team-add-member-form')
    cy.intercept('POST', '**/team/users').as('add')
    cy.get('#addTeamUser').click()
    cy.wait('@add').its('response.body').then((b) => expect(JSON.stringify(b)).to.match(/success|SUCCESS|true/))
    cy.get('#teamMsg').should('be.visible')
    cy.contains('#TeamDiv tr', EMAIL, { timeout: 15000 }).should('contain.text', 'USER')
    shot('TI-01-2-member-listed')
  })

  it('TI-02 the e-mail is in Mailpit with the set-password link', () => {
    mailTo(EMAIL).then((msg) => {
      expect(msg.Subject).to.eq('MyPlus - Password Reset')
      token1 = tokenOf(msg)
      // the link must open THIS stack: RESET_PASSWORD_URL from .env.local (deploy.ps1) — production would point elsewhere
      const link = /(https?:\/\/[^\s"<>]+changePassword\?token=[A-Za-z0-9-]+)/.exec(msg.Text || msg.HTML || '')
      expect(link, 'a set-password link in the e-mail').to.exist
      expect(link[1], 'the link opens this stack').to.match(new RegExp('^' + Cypress.config('baseUrl') + '/user/changePassword\\?token='))
      link1 = link[1]
    })
  })

  it('TI-03 before setting a password, the member cannot sign in', () => {
    signIn(EMAIL, 'a-guess-123')
    cy.location('pathname').should('include', '/login')
    shot('TI-03-1-cannot-sign-in-yet')
  })

  it('TI-04 the link opened on this stack → choose a password → sign in', () => {
    expect(link1, 'TI-02 found the link').to.be.a('string')
    cy.clearCookies()
    cy.visit(link1)   // exactly the link in the e-mail
    cy.get('#password').type(PASSWORD_1, { log: false })
    cy.get('#matchPassword').type(PASSWORD_1, { log: false })
    shot('TI-04-1-set-password-page')
    cy.intercept('POST', '**/user/savePassword').as('save')
    cy.contains('button', /submit|update|save/i).click()
    cy.wait('@save').its('response.statusCode').should('eq', 200)
    cy.location('pathname', { timeout: 15000 }).should('include', '/login')
    cy.get('.msg-bar').should('contain.text', SAVED).and('not.contain.text', 'null')
    shot('TI-04-2-back-on-login-with-message')
    signIn(EMAIL, PASSWORD_1)
    cy.location('pathname', { timeout: 20000 }).should('not.include', '/login')
    shot('TI-04-3-signed-in')
  })

  it('TI-05 the same link a second time is refused', () => {
    cy.clearCookies()
    cy.visit(`/user/changePassword?token=${token1}`)
    cy.get('#password').type(PASSWORD_2, { log: false })
    cy.get('#matchPassword').type(PASSWORD_2, { log: false })
    cy.contains('button', /submit|update|save/i).click()
    cy.get('#globalError', { timeout: 15000 }).should('be.visible')
    shot('TI-05-1-used-link-refused')
  })

  it('TI-06 Forgot password sends a fresh link; the new password signs in', () => {
    const since = new Date().toISOString()
    cy.clearCookies()
    cy.visit('/login')
    cy.get('#loginForgot').should('be.visible').click()
    cy.location('pathname').should('include', '/forgetPassword')
    cy.get('#email').type(EMAIL)
    cy.intercept('POST', '**/user/resetPassword').as('reset')
    cy.get('button[type="submit"]').click()
    cy.wait('@reset').its('response.statusCode').should('eq', 200)
    cy.location('pathname', { timeout: 15000 }).should('include', '/login')
    cy.get('.msg-bar').invoke('text').should('match', SENT).and('not.match', /^\s*null\s*$/)
    shot('TI-06-1-forgot-password-sent')
    mailTo(EMAIL, since).then((msg) => {
      const t2 = tokenOf(msg)
      expect(t2, 'a NEW token').to.not.eq(token1)
      cy.visit(`/user/changePassword?token=${t2}`)
      cy.get('#password').type(PASSWORD_2, { log: false })
      cy.get('#matchPassword').type(PASSWORD_2, { log: false })
      cy.contains('button', /submit|update|save/i).click()
      cy.location('pathname', { timeout: 15000 }).should('include', '/login')
      cy.get('.msg-bar').should('contain.text', SAVED)
      signIn(EMAIL, PASSWORD_2)
      cy.location('pathname', { timeout: 20000 }).should('not.include', '/login')
      shot('TI-06-2-signed-in-with-new-password')
    })
  })

  // TI-08 — the three sets the client put their accounts on (Doctor · Clinic reception · Standard)
  ;[
    { set: 'Doctor', tag: 'doc', dispenses: false },
    { set: 'Clinic reception', tag: 'rec', dispenses: false },
    { set: 'Standard', tag: 'std', dispenses: true },
  ].forEach(({ set, tag, dispenses }) => {
    it(`TI-08-${tag} a member on "${set}": owner sets it on the Team row → password from the e-mail → signs in with ${set}'s rights`, () => {
      const email = `ti.${run}.${tag}@myplus.test`
      const pw = `Clinic@${run}${tag}`
      const since = new Date().toISOString()
      // 1. owner adds the member on Team
      cy.loginAsPharmaOwner()
      cy.visit('/businessDashboard')
      cy.window().then((w) => w.showTeam())
      cy.get('#teamFirstName').type(set)
      cy.get('#teamLastName').type(`Test ${run}`)
      cy.get('#teamEmail').type(email)
      cy.get('#teamRole').select('USER', { force: true })
      cy.intercept('POST', '**/team/users').as('add')
      cy.get('#addTeamUser').click()
      cy.wait('@add').its('response.statusCode').should('eq', 200)
      // 2. on the member's row, the owner picks the set
      cy.intercept('POST', '**/team/permissions/assign').as('assign')
      cy.contains('#TeamDiv tr', email, { timeout: 15000 }).find('select.js-permset').then(($s) => {
        const opt = [...$s[0].options].find((o) => o.textContent.replace(/\s*·\s*built-in\s*$/, '').trim() === set)   // shown as 'Doctor · built-in'
        expect(opt, `"${set}" is offered on the row`).to.exist
        if ($s.val() === opt.value) return   // already on it (a new member starts on Standard): nothing to send
        // found AGAIN: the Team list re-draws after an add, and a dropdown held from before is detached
        cy.contains('#TeamDiv tr', email).find('select.js-permset').select(opt.value, { force: true })
        cy.wait('@assign').its('response.body').then((b) => expect(b && (b.success === true || b.status === 'SUCCESS'), JSON.stringify(b)).to.eq(true))
      })
      cy.request('/team/users').its('body').then((b) => {
        const m = (b.data || b.object || []).find((x) => x.email === email)
        cy.request('/team/permissions').its('body').then((pb) => {
          const s = ((pb.data || pb.object).sets || []).find((x) => x.name === set)
          expect(m.permissionSetId, `${email} is on "${set}"`).to.eq(s.id)
        })
      })
      shot(`TI-08-${tag}-1-team-row-set`)
      // 3. the member sets a password from the e-mail and signs in
      mailTo(email, since).then((msg) => {
        const link = /(https?:\/\/[^\s"<>]+changePassword\?token=[A-Za-z0-9-]+)/.exec(msg.Text || msg.HTML || '')
        expect(link, 'the set-password link').to.exist
        cy.clearCookies()
        cy.visit(link[1])
        cy.get('#password').type(pw, { log: false })
        cy.get('#matchPassword').type(pw, { log: false })
        cy.contains('button', /submit|update|save/i).click()
        cy.location('pathname', { timeout: 15000 }).should('include', '/login')
        cy.get('.msg-bar').should('contain.text', SAVED)
      })
      signIn(email, pw)
      cy.location('pathname', { timeout: 20000 }).should('not.include', '/login')
      // 4. what the server drew for THIS person: the dispense marker only for a set holding "dispense"
      cy.visit('/businessDashboard')
      cy.get('body').then(($b) => {
        expect($b.find('#rxCanDispense').length, `${set} ${dispenses ? 'may' : 'may NOT'} dispense`).to.eq(dispenses ? 1 : 0)
      })
      shot(`TI-08-${tag}-2-signed-in`)
    })
  })

  it('TI-07 Mailpit (http://localhost:8025) shows the set-password e-mails', () => {
    cy.visit(MAILPIT)
    cy.contains('MyPlus - Password Reset', { timeout: 15000 }).should('be.visible')
    shot('TI-07-1-mailpit-inbox')
  })
})
