/**
 * TEAM-1 / sign-in reasons / LOCK-1 — the Team screen after a member is added, as REAL manual test cases for the
 * Test Book (section 29). Every action is PERFORMED on the screen, ASSERTED and PHOTOGRAPHED (support/guide-capture.js);
 * docs/guides/build-testbook-section.js builds the page section from what this run recorded.
 *
 *   T1  a new member shows "Invited — no password yet", with Resend invitation
 *   T2  Resend: a fresh e-mail; the OLDER link stops working; the new one sets the password; the row turns Active
 *   T3  Remove asks why; the removed member is TOLD their access was removed (it used to say "Invalid Credentials")
 *   T4  Reactivate: Active again, and the member signs in with their own password
 *   T5  adding a removed person again says to Reactivate them (it used to say "Email already registered")
 *   L1  five wrong passwords lock the account (lockout never engaged before 14 Oct)
 *
 * TENANT. owner.pharma@ adds ONE throwaway member per case (<case>.<run>@myplus.test — .test never delivers) and
 * every case ends by removing it. Nothing is ever deleted: Remove is reversible, which is the point of TEAM-1.
 * Set-password e-mails are read from Mailpit (http://localhost:8025), where every development e-mail lands.
 *
 * Run headed (one case: --env guideOnly=T3):
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/docs/team-signin-guide-screens.cy.js --config trashAssetsBeforeRuns=false
 */
import { guideCapture } from '../../support/guide-capture'
import { HMS } from '../../support/hms-accounts'

const g = guideCapture({ outDir: 'cypress/guide-out/team-signin', section: 'Team members and sign-in', keepShots: true })
const RUN = String(Date.now()).slice(-6)
const MAILPIT = 'http://localhost:8025'
const PW = 'Kiwi#Lamp58'            // the member's own password (the rules refuse sequences like 123)
const WRONG = 'Wrong#Guess42'
const OWNER = HMS.OWNER
const made = []                     // every member this run added — after() removes any a failed case left active

const emailFor = (id) => `${id.toLowerCase()}.${RUN}@myplus.test`
const owner = () => cy.loginAs(OWNER, HMS.PW, '/getBusinessDashboardStats', `tb29-${RUN}-owner`)
const openTeam = () => {
  cy.visit('/businessDashboard')
  cy.window().then((w) => w.showTeam())
  cy.get('#TeamDiv').should('be.visible')
}
const row = (email) => cy.contains('#tableTeam tbody tr', email, { timeout: 15000 })
const member = (email) => cy.request('/team/users').its('body').then((b) => (b.data || []).find((x) => x.email === email))

/** Add a member through the Team screen's own request (a set-up step, not the subject of the case). */
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
/** Open the e-mailed link and choose a password — exactly what the person does. */
const choosePassword = (token) => {
  cy.clearCookies()
  cy.visit(`/user/changePassword?token=${token}`)
  cy.get('#password').type(PW, { log: false })
  cy.get('#matchPassword').type(PW, { log: false })
  cy.contains('button', /update|save|submit/i).click()
}
const signIn = (email, password) => {
  cy.clearCookies()
  cy.visit('/login')
  cy.get('input[name="username"]').type(email)
  cy.get('input[name="password"]').type(password, { log: false })
  cy.get('#loginSubmit').click()
}
/** A member who has chosen their own password (set-up for the cases that need one). */
const memberWithPassword = (email, first) => {
  owner()                           // testIsolation starts every case signed out — adding a member needs the owner
  addMemberRun(email, first)
  newestToken(email).then((t) => {
    choosePassword(t)
    cy.location('pathname', { timeout: 15000 }).should('include', '/login')
  })
}

describe('Test Book 29 — a team member after you add them (captured)', () => {
  beforeEach(() => cy.viewport(1366, 900))
  afterEach(g.write)

  // A case that fails half-way must not leave its member active on owner.pharma's team.
  after(() => {
    owner()
    made.forEach((email) => removeRun(email, 'guide cleanup'))
  })

  // ── T1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('T1', 'A new member shows Invited, with Resend invitation', () => {
    const email = emailFor('T1')
    g.testCase('T1', 'A new member shows “Invited — no password yet”, with Resend invitation', {
      tenant: OWNER, role: 'Owner (an administrator may do the same for ordinary members)',
      purpose: 'Until TEAM-1 the Team list could not tell an owner who had never set a password, so a lost invitation e-mail meant a member who could never sign in — and nobody knew.',
      prereq: [`Signed in as ${OWNER}.`],
      data: [`First name **Invite**, last name **Guide ${RUN}**`, `E-mail **${email}**`, 'Role **USER**'],
      rollback: 'Cleanup removes the member (Remove is reversible; nothing is deleted).',
    })
    owner()
    const a1 = g.act('Open **Team**. Fill **First name**, **Last name**, **E-mail**, choose role **USER**, and press **Add**.',
      ['A green note says the member was added and a set-password e-mail was sent.',
        'Their row appears with the badge **Invited — no password yet** and a **Resend invitation** button.'])
    openTeam()
    made.push(email)
    cy.get('#teamFirstName').type('Invite')
    cy.get('#teamLastName').type(`Guide ${RUN}`)
    cy.get('#teamEmail').type(email)
    cy.get('#teamRole').select('USER', { force: true })
    cy.intercept('POST', '**/team/users').as('add')
    cy.get('#addTeamUser').click()
    cy.wait('@add').its('response.statusCode').should('eq', 200)
    cy.get('#teamMsg').should('be.visible').and('contain.text', 'set-password email was sent')
    row(email).should('contain.text', 'Invited').find('.js-team-resend').should('be.visible')
    row(email).scrollIntoView()
    g.snap(a1, 'invited')

    const c1 = g.act(`On the row for **${email}**, press **Remove**, type the reason **guide cleanup**, and press **Remove**.`,
      ['The row says **Removed** and offers **Reactivate**.'], { cleanup: true })
    row(email).find('.js-team-remove').click()
    cy.get('.uiC-input').should('be.visible').type('guide cleanup')
    cy.get('[data-ui-confirm="ok"]').click()
    row(email).should('contain.text', 'Removed').find('.js-team-reactivate').should('be.visible')
    g.snap(c1, 'removed')
  })

  // ── T2 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('T2', 'Resend invitation — the older link stops working', () => {
    const email = emailFor('T2')
    let first
    let second
    g.testCase('T2', 'Resend invitation: a fresh e-mail, the older link stops working, the new one sets the password', {
      tenant: OWNER, role: 'Owner',
      purpose: 'Links used to pile up — one test account held 56 live set-password links. Now only the newest works, as Shopify does when an invitation is sent again, and the row turns Active once the person has chosen a password.',
      prereq: [`Signed in as ${OWNER}.`, 'Mailpit open at http://localhost:8025 (where development e-mail lands).'],
      data: [`A member **${email}** added for this case (set-up below)`, `Their new password **${PW}**`],
      rollback: 'Cleanup removes the member.',
    })
    owner()
    addMemberRun(email, 'Resend')
    newestToken(email).then((t) => { first = t })

    const a1 = g.act(`Open **Team**. On the row for **${email}** (badge **Invited**), press **Resend invitation**.`,
      ['A green note reads **A new set-password e-mail was sent. Older links no longer work.**', 'A second e-mail reaches Mailpit.'])
    openTeam()
    const since = new Date().toISOString()
    row(email).find('.js-team-resend').click()
    cy.get('#teamMsg').should('be.visible').and('contain.text', 'Older links no longer work')
    newestToken(email, since).then((t) => { second = t; expect(second, 'a NEW link').to.not.eq(first) })
    g.snap(a1, 'resent')

    const a2 = g.act('Open the link from the **first** e-mail, type a password twice, and press **Update Password**.',
      ['The page refuses it in red — that link was cancelled when the new one was sent.'])
    cy.then(() => {
      choosePassword(first)
      cy.get('#globalError', { timeout: 15000 }).should('be.visible')
    })
    g.snap(a2, 'old-link-refused')

    const a3 = g.act('Open the link from the **second** e-mail, type the password twice, and press **Update Password**.',
      ['The login page opens, saying the password was reset.'])
    cy.then(() => {
      choosePassword(second)
      cy.location('pathname', { timeout: 15000 }).should('include', '/login')
    })
    g.snap(a3, 'new-link-works')

    const a4 = g.act('Back as the owner, open **Team** and find the row.',
      ['The badge now reads **Active**, and **Resend invitation** is gone.'])
    owner()
    openTeam()
    row(email).should('contain.text', 'Active').find('.js-team-resend').should('not.exist')
    row(email).scrollIntoView()
    g.snap(a4, 'active')

    const c1 = g.act(`Press **Remove** on **${email}**, reason **guide cleanup**.`, ['The row says **Removed**.'], { cleanup: true })
    row(email).find('.js-team-remove').click()
    cy.get('.uiC-input').type('guide cleanup')
    cy.get('[data-ui-confirm="ok"]').click()
    row(email).should('contain.text', 'Removed')
    g.snap(c1, 'removed')
  })

  // ── T3 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('T3', 'Remove asks why; the removed member is told why they cannot sign in', () => {
    const email = emailFor('T3')
    g.testCase('T3', 'Remove asks why; the removed member is told their access was removed', {
      tenant: `${OWNER}, then the member ${email}`, role: 'Owner, then the removed member',
      purpose: 'Removing is how a business offboards someone — reversibly. Before 14 Oct the removed person saw “Invalid Credentials” and would reset a password that was never the problem (the reset works, and sign-in is refused again).',
      prereq: [`Signed in as ${OWNER}.`, 'Use a second browser profile (or sign out) for the member’s sign-in.'],
      data: [`A member **${email}** who has chosen the password **${PW}** (set-up below)`],
      rollback: 'The member is left Removed — that is the clean-up. Reactivate (T4) is the reverse.',
    })
    memberWithPassword(email, 'Remove')

    const a1 = g.act(`As the owner, open **Team** and press **Remove** on **${email}**. Press **Remove** in the dialog without typing a reason.`,
      ['The dialog says the member is signed out within 15 minutes and nothing is deleted.', 'It refuses to go on: **Say why, e.g. left the clinic.**'])
    owner()
    openTeam()
    row(email).find('.js-team-remove').click()
    cy.get('.uiC-card').should('be.visible').and('contain.text', 'within 15 minutes')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('.uiC-card').should('contain.text', 'Say why')
    g.snap(a1, 'asks-why')

    const a2 = g.act('Type the reason **Left the clinic** and press **Remove**.',
      ['The row now says **Removed**, is greyed, and offers **Reactivate** instead of Edit access.'])
    cy.get('.uiC-input').type('Left the clinic')
    cy.get('[data-ui-confirm="ok"]').click()
    row(email).should('contain.text', 'Removed').find('.js-team-reactivate').should('be.visible')
    row(email).scrollIntoView()
    g.snap(a2, 'removed')

    const a3 = g.act(`Sign out. On the login page, sign in as **${email}** with **${PW}**.`,
      ['Sign-in is refused, and the red bar reads **Your access to this business was removed. Ask the owner if this is a mistake.**',
        'It does **not** say “Invalid Credentials”, and no new empty business is made for them.'])
    signIn(email, PW)
    cy.location('pathname', { timeout: 15000 }).should('include', '/login')
    cy.get('.msg-bar.error').should('be.visible').and('contain.text', 'access to this business was removed')
      .and('not.contain.text', 'Invalid Credentials')
    g.snap(a3, 'told-why')

    const c1 = g.act('Nothing to undo — the member stays **Removed**. (T4 reactivates one.)', ['—'], { cleanup: true })
    g.snap(c1, 'left-removed')
  })

  // ── T4 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('T4', 'Reactivate — Active again, signs in with their own password', () => {
    const email = emailFor('T4')
    g.testCase('T4', 'Reactivate: Active again, and the member signs in with their own password', {
      tenant: `${OWNER}, then the member ${email}`, role: 'Owner, then the member',
      purpose: 'Removal is reversible: a member who comes back keeps their password and their permission set — nothing is re-entered.',
      prereq: [`Signed in as ${OWNER}.`],
      data: [`A member **${email}** with password **${PW}**, removed for this case (set-up below)`],
      rollback: 'Cleanup removes the member again.',
    })
    memberWithPassword(email, 'Return')
    owner()
    removeRun(email, 'guide set-up')

    const a1 = g.act(`Open **Team**. On the **Removed** row for **${email}**, press **Reactivate**, then **Reactivate** in the dialog.`,
      ['The row says **Active** again, with **Edit access** and **Remove**.'])
    openTeam()
    row(email).find('.js-team-reactivate').click()
    cy.get('.uiC-card').should('be.visible')
    cy.get('[data-ui-confirm="ok"]').click()
    row(email).should('contain.text', 'Active').find('.js-team-remove').should('exist')
    row(email).scrollIntoView()
    g.snap(a1, 'reactivated')

    const a2 = g.act(`Sign out, and sign in as **${email}** with **${PW}**.`, ['They are signed in — the login page is left behind.'])
    signIn(email, PW)
    cy.location('pathname', { timeout: 20000 }).should('not.include', '/login')
    g.snap(a2, 'signed-in')

    const c1 = g.act(`As the owner, press **Remove** on **${email}**, reason **guide cleanup**.`, ['The row says **Removed**.'], { cleanup: true })
    owner()
    openTeam()
    row(email).find('.js-team-remove').click()
    cy.get('.uiC-input').type('guide cleanup')
    cy.get('[data-ui-confirm="ok"]').click()
    row(email).should('contain.text', 'Removed')
    g.snap(c1, 'removed')
  })

  // ── T5 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('T5', 'Adding a removed person again says to Reactivate them', () => {
    const email = emailFor('T5')
    g.testCase('T5', 'Adding a removed person again says to Reactivate them', {
      tenant: OWNER, role: 'Owner',
      purpose: 'Before 14 Oct the form said only “Email already registered” — and in the clinic it said “use Link login”, whose list hides removed members: a dead end either way.',
      prereq: [`Signed in as ${OWNER}.`],
      data: [`A member **${email}**, removed for this case (set-up below)`],
      rollback: 'Nothing is created — the refusal writes nothing. The member stays Removed.',
    })
    owner()
    addMemberRun(email, 'Again')
    removeRun(email, 'guide set-up')

    const a1 = g.act(`Open **Team** and add **${email}** again (any name, role **USER**), pressing **Add**.`,
      ['A red note reads **This person was removed from your team. Reactivate them on the Team screen instead of adding them again.**',
        'No second row appears.'])
    openTeam()
    cy.get('#teamFirstName').type('Again')
    cy.get('#teamLastName').type(`Guide ${RUN}`)
    cy.get('#teamEmail').type(email)
    cy.get('#teamRole').select('USER', { force: true })
    cy.get('#addTeamUser').click()
    cy.get('#teamMsg').should('be.visible').and('contain.text', 'Reactivate them on the Team screen')
    cy.get('#tableTeam tbody tr').filter(`:contains("${email}")`).should('have.length', 1)
    g.snap(a1, 'told-to-reactivate')

    const c1 = g.act('Nothing to undo — the refusal created nothing.', ['—'], { cleanup: true })
    g.snap(c1, 'nothing-created')
  })

  // ── L1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('L1', 'Five wrong passwords lock the account', () => {
    const email = emailFor('L1')
    g.testCase('L1', 'Five wrong passwords lock the account for 30 minutes', {
      tenant: `the member ${email}`, role: 'Anyone at the login page',
      purpose: 'Until 14 Oct the lock never engaged: the failed-attempt count was saved and then rolled back by the refusal itself, so a password could be guessed without limit. Never try this on a shared demo account — it locks it for 30 minutes.',
      prereq: ['A throwaway member made for this case (set-up below) — never a real or shared account.'],
      data: [`Member **${email}**`, `Wrong password **${WRONG}**`],
      rollback: 'Cleanup removes the member. The lock lifts by itself after 30 minutes.',
    })
    owner()
    addMemberRun(email, 'Lock')

    const a1 = g.act(`On the login page, sign in as **${email}** with the wrong password **${WRONG}** — five times.`,
      ['Each time the red bar reads **Invalid Credentials**.'])
    for (let i = 1; i <= 5; i++) {
      signIn(email, WRONG)
      cy.get('.msg-bar.error').should('be.visible').and('contain.text', 'Invalid Credentials')
    }
    g.snap(a1, 'fifth-wrong')

    const a2 = g.act('Try a sixth time.',
      ['The red bar now reads **Your account is temporarily locked after several failed attempts. Please wait a few minutes and try again.**'])
    signIn(email, WRONG)
    cy.get('.msg-bar.error').should('be.visible').and('contain.text', 'temporarily locked')
    g.snap(a2, 'locked')

    const c1 = g.act(`As the owner, open **Team** and press **Remove** on **${email}**, reason **guide cleanup**.`,
      ['The row says **Removed**. (The lock would have lifted by itself after 30 minutes.)'], { cleanup: true })
    owner()
    openTeam()
    row(email).find('.js-team-remove').click()
    cy.get('.uiC-input').type('guide cleanup')
    cy.get('[data-ui-confirm="ok"]').click()
    row(email).should('contain.text', 'Removed')
    g.snap(c1, 'removed')
  })
})
