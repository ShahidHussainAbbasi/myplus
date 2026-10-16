/**
 * RESET-1 — a set-password link that does not work says WHY, as REAL manual test cases for the Test Book (section 35).
 * Every action is PERFORMED on the screen, ASSERTED and PHOTOGRAPHED (support/guide-capture.js);
 * docs/guides/build-testbook-section.js builds the page section from what this run recorded.
 *
 *   P1  a link used once, opened again: the reason + "Send me a new link"; no password boxes
 *   P2  Forgot password twice: the FIRST e-mail's link says it was replaced; the newest works
 *   P3  a link older than an hour: "expired — links work for 1 hour" + Send me a new link
 *   P4  the form is open, then a newer e-mail is sent: Update Password says replaced — not "Invalid token."
 *   P5  Send me a new link → Forgot password → the newest e-mail sets the password; sign-in works
 *
 * Found 2026-10-15: a pharmacy owner was told "Invalid token." four times while submitting a page that held ANOTHER
 * account's already-used link; nothing on screen could say so (slices/reset-1-reset-link-reasons.md).
 *
 * TENANT. owner.pharma@ adds ONE throwaway member per case (<case>.<run>@myplus.test — .test never delivers) and every
 * case ends by removing it. Set-password e-mails are read from Mailpit (http://localhost:8025). P3's "an hour passes"
 * is the cypress.config.js task expireResetToken (local database only).
 *
 * Run headed (one case: --env guideOnly=P3):
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/docs/reset-1-link-guide-screens.cy.js --config trashAssetsBeforeRuns=false
 */
import { guideCapture } from '../../support/guide-capture'
import { HMS } from '../../support/hms-accounts'

const g = guideCapture({ outDir: 'cypress/guide-out/reset-1', section: 'Set-password links', keepShots: true })
const RUN = String(Date.now()).slice(-6)
const MAILPIT = 'http://localhost:8025'
const PW = 'Kiwi#Lamp58'            // passes the password rules (no 3-sequences like 123)
const OWNER = HMS.OWNER
const made = []

// The words the page must show (D4). Matched loosely so a comma does not fail a case; never "Invalid token".
const USED = /already been used, or a newer e-mail replaced it/i
const EXPIRED = /expired.*1 hour/i

const emailFor = (id) => `${id.toLowerCase()}.${RUN}@myplus.test`
const owner = () => cy.loginAs(OWNER, HMS.PW, '/getBusinessDashboardStats', `tb35-${RUN}-owner`)
const member = (email) => cy.request('/team/users').its('body').then((b) => (b.data || []).find((x) => x.email === email))
const addMemberRun = (email, first) => {
  made.push(email)
  return cy.postJson('/team/users', { firstName: first, lastName: `Guide ${RUN}`, email, role: 'USER', storeIds: [] })
    .then((r) => expect(r.body && r.body.data && r.body.data.userId, `member added: ${JSON.stringify(r.body).slice(0, 160)}`).to.be.ok)
}
const removeRun = (email, reason) => member(email).then((m) => {
  if (m && m.status !== 'INACTIVE') cy.postJson(`/team/users/${m.userId}/remove`, { reason })
})

/** The newest set-password link e-mailed to `email` (after `since`, when given), polled from Mailpit. */
const newestToken = (email, since) => {
  const look = (tries) => cy.request(`${MAILPIT}/api/v1/messages?limit=100`).then((r) => {
    const ms = (r.body.messages || []).filter((x) => (x.To || []).some((a) => a.Address === email) && (!since || x.Created > since))
    if (ms.length) {
      return cy.request(`${MAILPIT}/api/v1/message/${ms[0].ID}`).its('body')
        .then((m) => /token=([A-Za-z0-9-]+)/.exec(m.Text || m.HTML || '')[1])
    }
    if (tries <= 0) throw new Error(`no set-password e-mail to ${email} in Mailpit`)
    return cy.wait(1000).then(() => look(tries - 1))
  })
  return look(20)
}
const openLink = (token) => { cy.clearCookies(); cy.visit(`/user/changePassword?token=${token}`) }
const typePassword = () => {
  cy.get('#password').type(PW, { log: false })
  cy.get('#matchPassword').type(PW, { log: false })
}
const pressUpdate = () => cy.contains('button', /update|save|submit/i).click()
/** A dead link: the reason, the button, and NO form to type into. */
const shouldSayDead = (words) => {
  cy.get('#resetLinkProblem', { timeout: 15000 }).should('be.visible').invoke('text').should('match', words).and('not.match', /invalid token/i)
  cy.get('#resetNewLink').should('be.visible')
  cy.get('#password').should('not.exist')
}
/** Forgot password, on the screen, for `email`. */
const forgotPassword = (email) => {
  cy.clearCookies()
  cy.visit('/login')
  cy.get('#loginForgot').click()
  cy.location('pathname').should('include', '/forgetPassword')
  cy.get('#email').type(email)
  cy.intercept('POST', '**/user/resetPassword').as('forgot')
  cy.get('button[type="submit"]').click()
  cy.wait('@forgot').its('response.statusCode').should('eq', 200)
}
const signIn = (email) => {
  cy.clearCookies()
  cy.visit('/login')
  cy.get('input[name="username"]').type(email)
  cy.get('input[name="password"]').type(PW, { log: false })
  cy.get('#loginSubmit').click()
}
const cleanupAct = (email) => {
  const c1 = g.act(`As **${OWNER}**, open **Team**, press **Remove** on **${email}**, reason **guide cleanup**.`,
    ['The row says **Removed**.'], { cleanup: true })
  owner()
  removeRun(email, 'guide cleanup')
  member(email).its('status').should('eq', 'INACTIVE')
  return c1
}

describe('Test Book 35 — a set-password link that does not work says why (captured)', () => {
  beforeEach(() => cy.viewport(1366, 900))
  afterEach(g.write)
  after(() => {
    owner()
    made.forEach((email) => removeRun(email, 'guide cleanup'))
  })

  // ── P1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('P1', 'A used link, opened again, says it was used', () => {
    const email = emailFor('P1')
    let token
    g.testCase('P1', 'A link used once, opened again: it says so, and offers a new link', {
      tenant: OWNER, role: 'The new team member (anyone who received a set-password e-mail)',
      purpose: 'Until RESET-1 a used link showed the password form, and only after typing a password twice said “Invalid token.” — the same words for every problem.',
      prereq: ['Mailpit open at http://localhost:8025 (where development e-mail lands).'],
      data: [`A member **${email}** added by ${OWNER} for this case (set-up)`, `Their password **${PW}**`],
      rollback: 'Cleanup removes the member (reversible).',
    })
    owner()
    addMemberRun(email, 'Used')
    newestToken(email).then((t) => { token = t })

    const a1 = g.act('Open the link in the e-mail, type the password twice, and press **Update Password**.',
      ['The login page opens, saying **Password reset successfully**.'])
    cy.then(() => { openLink(token); typePassword(); pressUpdate() })
    cy.location('pathname', { timeout: 15000 }).should('include', '/login')
    g.snap(a1, 'password-set')

    const a2 = g.act('Open the **same** link again.',
      ['In red: **This link has already been used, or a newer e-mail replaced it. Ask for a new link and use the newest e-mail.**',
        'A **Send me a new link** button. No password boxes.'])
    cy.then(() => openLink(token))
    shouldSayDead(USED)
    g.snap(a2, 'used-link')

    g.snap(cleanupAct(email), 'removed')
  })

  // ── P2 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('P2', 'Forgot password twice: the older link says it was replaced', () => {
    const email = emailFor('P2')
    let first
    let second
    g.testCase('P2', 'Forgot password twice: the first e-mail’s link says it was replaced; the newest works', {
      tenant: OWNER, role: 'A team member who asked for a link twice',
      purpose: 'Only the newest link works (TEAM-1). People open the e-mail at the top of the wrong pile; the old link must say so, not “Invalid token.”',
      prereq: ['Mailpit open at http://localhost:8025.'],
      data: [`A member **${email}** added for this case`, `Their password **${PW}**`],
      rollback: 'Cleanup removes the member.',
    })
    owner()
    addMemberRun(email, 'Twice')
    const s0 = new Date().toISOString()

    const a1 = g.act(`On the login page press **Forgot password?**, enter **${email}**, press **Reset Password** — then do it **again**.`,
      ['Each time the login page says an e-mail is on its way.', 'Mailpit shows **two** new e-mails to that address.'])
    forgotPassword(email)
    newestToken(email, s0).then((t) => { first = t })
    cy.then(() => {
      const s1 = new Date().toISOString()
      forgotPassword(email)
      newestToken(email, s1).then((t) => { second = t; expect(second, 'a NEW link').to.not.eq(first) })
    })
    g.snap(a1, 'asked-twice')

    const a2 = g.act('Open the link in the **older** e-mail.',
      ['In red: **This link has already been used, or a newer e-mail replaced it…**, and **Send me a new link**. No password boxes.'])
    cy.then(() => openLink(first))
    shouldSayDead(USED)
    g.snap(a2, 'older-link')

    const a3 = g.act('Open the link in the **newest** e-mail, type the password twice, press **Update Password**.',
      ['The login page opens, saying **Password reset successfully**.'])
    cy.then(() => { openLink(second); typePassword(); pressUpdate() })
    cy.location('pathname', { timeout: 15000 }).should('include', '/login')
    g.snap(a3, 'newest-works')

    g.snap(cleanupAct(email), 'removed')
  })

  // ── P3 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('P3', 'A link older than an hour says it expired', () => {
    const email = emailFor('P3')
    let token
    g.testCase('P3', 'A link older than an hour says it expired, and offers a new link', {
      tenant: OWNER, role: 'A team member who opened the e-mail late',
      purpose: 'Links work for 1 hour. An expired link used to say “Invalid token.” — and from the second try on, the token was already deleted, so not even auth knew it had expired.',
      prereq: ['Mailpit open at http://localhost:8025.',
        'Set-up only: the hour passing is simulated on the local database (Cypress task expireResetToken). By hand: wait more than an hour before opening the link.'],
      data: [`A member **${email}** added for this case`],
      rollback: 'Cleanup removes the member.',
    })
    owner()
    addMemberRun(email, 'Late')
    newestToken(email).then((t) => { token = t })
    cy.then(() => cy.task('expireResetToken', { token })).should('eq', 1)

    const a1 = g.act('Open the link in the e-mail (more than an hour after it was sent).',
      ['In red: **This link has expired — links work for 1 hour. Ask for a new link.**', '**Send me a new link**. No password boxes.'])
    cy.then(() => openLink(token))
    shouldSayDead(EXPIRED)
    g.snap(a1, 'expired')

    const a2 = g.act('Open the **same** link once more.', ['It still says **expired** — it does not turn into a different message.'])
    cy.then(() => openLink(token))
    shouldSayDead(EXPIRED)
    g.snap(a2, 'still-expired')

    g.snap(cleanupAct(email), 'removed')
  })

  // ── P4 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('P4', 'The link dies while the form is open: Update Password says why', () => {
    const email = emailFor('P4')
    let token
    g.testCase('P4', 'The form is open, then a newer e-mail is sent: Update Password says the link was replaced', {
      tenant: OWNER, role: 'A team member with the page left open in a tab',
      purpose: 'This is the 15 Oct report exactly: a page left open from an earlier link, submitted after that link stopped working, said “Invalid token.” four times.',
      prereq: ['Mailpit open at http://localhost:8025.'],
      data: [`A member **${email}** added for this case`, `Password **${PW}**`],
      rollback: 'Cleanup removes the member.',
    })
    owner()
    addMemberRun(email, 'Open')
    newestToken(email).then((t) => { token = t })

    const a1 = g.act('Open the link in the e-mail. Leave the page open.', ['The password form is shown (the link is good).'])
    cy.then(() => openLink(token))
    cy.get('#password').should('be.visible')
    cy.get('#resetLinkProblem').should('not.exist')
    g.snap(a1, 'form-open')

    const a2 = g.act(`In another tab, ask for a new link for **${email}** (Forgot password?). Back on the open page, type the password twice and press **Update Password**.`,
      ['In red: **This link has already been used, or a newer e-mail replaced it…** — not “Invalid token.”', 'The password is NOT changed.'])
    cy.then(() => {
      const since = new Date().toISOString()
      cy.postForm('/user/resetPassword', { email }).its('status').should('eq', 200)
      newestToken(email, since)
    })
    typePassword()
    cy.intercept('POST', '**/user/savePassword').as('save')
    pressUpdate()
    cy.wait('@save').its('response.statusCode').should('eq', 400)
    cy.get('#globalError').should('be.visible').invoke('text').should('match', USED).and('not.match', /invalid token/i)
    cy.location('pathname').should('include', '/user/changePassword')
    g.snap(a2, 'replaced-while-open')

    g.snap(cleanupAct(email), 'removed')
  })

  // ── P5 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('P5', 'Send me a new link leads to a working link', () => {
    const email = emailFor('P5')
    let token
    g.testCase('P5', '“Send me a new link” → Forgot password → the newest e-mail sets the password; sign-in works', {
      tenant: OWNER, role: 'A team member on a dead link',
      purpose: 'A dead link must lead somewhere. The button goes to Forgot password; the person never has to find it.',
      prereq: ['Mailpit open at http://localhost:8025.'],
      data: [`A member **${email}** added for this case`, `Password **${PW}**`],
      rollback: 'Cleanup removes the member.',
    })
    owner()
    addMemberRun(email, 'Again')
    newestToken(email).then((t) => { token = t })
    cy.then(() => cy.task('expireResetToken', { token })).should('eq', 1)

    const a1 = g.act('Open the (expired) link and press **Send me a new link**.', ['The **Reset Password** page opens, asking for the e-mail.'])
    cy.then(() => openLink(token))
    cy.get('#resetNewLink').click()
    cy.location('pathname').should('include', '/forgetPassword')
    cy.get('#email').should('be.visible')
    g.snap(a1, 'forgot-page')

    const a2 = g.act(`Enter **${email}**, press **Reset Password**, open the **newest** e-mail’s link, type the password twice, press **Update Password**.`,
      ['The login page opens, saying **Password reset successfully**.'])
    const since = new Date().toISOString()
    cy.get('#email').type(email)
    cy.intercept('POST', '**/user/resetPassword').as('forgot')
    cy.get('button[type="submit"]').click()
    cy.wait('@forgot').its('response.statusCode').should('eq', 200)
    newestToken(email, since).then((t) => { openLink(t); typePassword(); pressUpdate() })
    cy.location('pathname', { timeout: 15000 }).should('include', '/login')
    g.snap(a2, 'new-link-set')

    const a3 = g.act(`Sign in as **${email}** with **${PW}**.`, ['Signed in — the page is no longer the login page.'])
    signIn(email)
    cy.location('pathname', { timeout: 20000 }).should('not.include', '/login')
    g.snap(a3, 'signed-in')

    g.snap(cleanupAct(email), 'removed')
  })
})
