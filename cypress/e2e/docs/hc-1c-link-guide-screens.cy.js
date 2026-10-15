/**
 * HC-1c — a Hospital / Clinic links to its pharmacy (a separate business), as REAL step-by-step manual cases for the
 * Test Book (§34). Written BEFORE the build (the user's rule): every action is PERFORMED on the screen, ASSERTED and
 * PHOTOGRAPHED (support/guide-capture.js), and each case ends with its clean-up.
 * Design: microservices/docs/slices/hc-1cd-clinic-pharmacy-link.md (D1: one active pharmacy per clinic).
 *
 *   L1  the pharmacy owner turns on "Clinics may send us prescriptions" (Pharmacy → Linked clinics)
 *   L2  the clinic owner finds that pharmacy by name — a pharmacy that has NOT turned it on is not found
 *   L3  the clinic sends a link request; it waits for the pharmacy
 *   L4  the pharmacy accepts; both sides read Linked, and the clinic's checklist ticks "Link your pharmacy"
 *   L5  while linked, the clinic cannot request a second pharmacy (one at a time, today)
 *   L6  the pharmacy ends the link with a reason; both sides read Ended
 *
 * EXPECTED RED on the build it was written against: there is no Linked clinics / Linked pharmacy screen and no link API.
 *
 * TENANTS: the run provisions its own clinic and two pharmacies through the console's own request (names carry the
 * run id, e-mails end in .test, set-password links come from Mailpit) and suspends all three at the end.
 *
 * Run headed:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/docs/hc-1c-link-guide-screens.cy.js --config trashAssetsBeforeRuns=false
 */
import { guideCapture } from '../../support/guide-capture'

const g = guideCapture({ outDir: 'cypress/guide-out/hc-1c', section: 'A clinic links to its pharmacy', keepShots: true })
const RUN = String(Date.now()).slice(-6)
const MAILPIT = 'http://localhost:8025'
const PW = 'Kiwi#Lamp58'
const CLINIC = { org: `HC1C Clinic ${RUN}`, email: `hc1c.clinic.${RUN}@myplus.test`, type: 'hospital_clinic' }
const PHARMA = { org: `HC1C Pharmacy ${RUN}`, email: `hc1c.pharma.${RUN}@myplus.test`, type: 'pharmacy' }
const HIDDEN = { org: `HC1C Hidden Pharmacy ${RUN}`, email: `hc1c.hidden.${RUN}@myplus.test`, type: 'pharmacy' }

const setPasswordLink = (email) => {
  const look = (tries) => cy.request(`${MAILPIT}/api/v1/messages?limit=100`).then((r) => {
    const m = (r.body.messages || []).find((x) => (x.To || []).some((a) => a.Address === email))
    if (m) return cy.request(`${MAILPIT}/api/v1/message/${m.ID}`).its('body').then((b) => /token=([A-Za-z0-9-]+)/.exec(b.Text || b.HTML || '')[1])
    if (tries <= 0) throw new Error(`no set-password e-mail to ${email}`)
    return cy.wait(1000).then(() => look(tries - 1))
  })
  return look(20)
}
const choosePassword = (token) => {
  cy.clearCookies()
  cy.visit(`/user/changePassword?token=${token}`)
  cy.get('#password').type(PW, { log: false })
  cy.get('#matchPassword').type(PW, { log: false })
  cy.contains('button', /update|save|submit/i).click()
  cy.location('pathname', { timeout: 15000 }).should('include', '/login')
}
const signIn = (email) => {
  cy.clearCookies()
  cy.visit('/login')
  cy.get('input[name="username"]').type(email)
  cy.get('input[name="password"]').type(PW, { log: false })
  cy.get('#loginSubmit').click()
  cy.location('pathname', { timeout: 20000 }).should('not.include', '/login')
}
const provision = (t) => cy.request({ method: 'POST', url: '/platform/provisionTenant', form: true, body: {
  organizationName: t.org, email: t.email, firstName: 'Tahseen', lastName: `Test ${RUN}`, plan: 'PRO', businessType: t.type } })
  .its('body.success').should('eq', true)
const openLinkedClinics = () => {
  cy.visit('/businessDashboard')
  cy.get('#snavPharmacy > .snav-btn', { timeout: 20000 }).click()
  cy.get('#navLinkedClinics').click()
  cy.get('#LinkedClinicsDiv').should('be.visible')
}
const openLinkedPharmacy = () => {
  cy.visit('/clinicDashboard')
  cy.get('#clinNavPharmacyLink', { timeout: 20000 }).click()
  cy.get('#PharmacyLinkDiv').should('be.visible')
}

describe('Test Book — HC-1c a clinic links to its pharmacy (captured)', () => {
  beforeEach(() => cy.viewport(1366, 900))
  afterEach(g.write)

  before(() => {
    // Set-up (not under test — HC-1a walks this screen): three tenants through the console's own request, passwords set.
    cy.loginAsOperator()
    ;[CLINIC, PHARMA, HIDDEN].forEach(provision)
    ;[CLINIC, PHARMA, HIDDEN].forEach((t) => setPasswordLink(t.email).then(choosePassword))
  })

  after(() => {
    cy.loginAsOperator()
    ;[CLINIC, PHARMA, HIDDEN].forEach((t) =>
      cy.request(`/platform/organizations?page=0&size=20&q=${encodeURIComponent(t.org)}`).then((r) => {
        const d = (r.body && (r.body.data || r.body.object || r.body)) || {}
        ;(d.rows || []).filter((o) => o && o.name === t.org && o.status === 'ACTIVE').forEach((o) =>
          cy.request({ method: 'POST', url: '/platform/status', form: true, failOnStatusCode: false,
            body: { organizationId: o.id, status: 'SUSPENDED', reason: 'guide cleanup' } }))
      }))
  })

  // ── L1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('L1', 'The pharmacy accepts prescriptions from clinics', () => {
    g.testCase('L1', 'The pharmacy turns on "Clinics may send us prescriptions"', {
      tenant: PHARMA.email, role: 'Owner of a Pharmacy & Pharmaceutical business',
      purpose: 'A pharmacy is found by clinics only if it chooses to be. Off by default; nothing is shared by turning it on — it only lets a clinic ask.',
      prereq: ['A new Pharmacy & Pharmaceutical tenant whose owner has a password (the run provisions it).'],
      data: ['—'],
      rollback: 'The tenant is suspended at the end of the run.',
    })
    const a1 = g.act('Sign in. Open **Pharmacy → Linked clinics**.',
      ['**Clinics may send us prescriptions** is **off**.', 'The list of clinics is empty.'])
    signIn(PHARMA.email)
    openLinkedClinics()
    cy.get('#lcAccept').should('not.be.checked')
    cy.get('#lcLinks').should('contain.text', 'No clinics')
    g.snap(a1, 'off')

    const a2 = g.act('Turn **Clinics may send us prescriptions** on.', ['A note says it is saved; clinics can now find this pharmacy by its name.'])
    cy.intercept('POST', '**/links/settings*').as('saveSetting')
    cy.get('#lcAccept').check({ force: true })
    cy.wait('@saveSetting').its('response.statusCode').should('eq', 200)
    cy.get('#lcMsg').should('be.visible')
    g.snap(a2, 'on')
  })

  // ── L2 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('L2', 'The clinic finds the pharmacy by name', () => {
    g.testCase('L2', 'The clinic finds its pharmacy by name — a pharmacy that has not opted in is not found', {
      tenant: CLINIC.email, role: 'Owner of a Hospital / Clinic',
      purpose: 'Only pharmacies that chose to accept clinics can be found, and only their name is shown — never their stock, prices or customers.',
      prereq: ['L1 has run.', `A second pharmacy, **${HIDDEN.org}**, has NOT turned the switch on.`],
      data: [`Search **${RUN}** (both pharmacies' names contain it)`],
      rollback: 'Nothing is saved.',
    })
    const a1 = g.act(`Sign in. Open **Linked pharmacy**, type **${RUN}** and press **Enter**.`,
      [`**${PHARMA.org}** is listed with **Send link request**.`, `**${HIDDEN.org}** is **not** listed.`])
    signIn(CLINIC.email)
    openLinkedPharmacy()
    cy.get('#plStatus').should('contain.text', 'No pharmacy linked')
    cy.get('#plSearch').type(RUN + '{enter}')   // both pharmacies' names carry the run id; other runs' are suspended
    cy.get('#plResults', { timeout: 15000 }).should('contain.text', PHARMA.org)
    cy.get('#plResults').should('not.contain.text', HIDDEN.org)
    g.snap(a1, 'found')
  })

  // ── L3 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('L3', 'The clinic sends a link request', () => {
    g.testCase('L3', 'The clinic sends a link request — it waits for the pharmacy', {
      tenant: CLINIC.email, role: 'Clinic owner',
      purpose: 'A link needs BOTH sides: the clinic asks, the pharmacy decides.',
      prereq: ['L2 has run.'], data: [`Pharmacy **${PHARMA.org}**`],
      rollback: 'Ended in L6.',
    })
    const a1 = g.act(`Find **${PHARMA.org}** again and press **Send link request**, then confirm.`,
      [`The status reads **Waiting for ${PHARMA.org} to accept**.`, 'The search is gone — one pharmacy at a time.'])
    signIn(CLINIC.email)
    openLinkedPharmacy()
    cy.get('#plSearch').type(PHARMA.org + '{enter}')
    cy.contains('#plResults tr', PHARMA.org, { timeout: 15000 }).find('.pl-request').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.get('#plStatus', { timeout: 15000 }).should('contain.text', 'Waiting').and('contain.text', PHARMA.org)
    cy.get('#plSearch').should('not.be.visible')
    g.snap(a1, 'requested')
  })

  // ── L4 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('L4', 'The pharmacy accepts; both sides are linked', () => {
    g.testCase('L4', 'The pharmacy accepts the request — both sides read Linked', {
      tenant: `${PHARMA.email}, then ${CLINIC.email}`, role: 'Pharmacy owner, then clinic owner',
      purpose: 'Accepting is what lets the clinic\'s doctors send prescriptions here (HC-1d). The clinic\'s checklist ticks.',
      prereq: ['L3 has run.'], data: ['—'], rollback: 'Ended in L6.',
    })
    const a1 = g.act(`As the pharmacy owner open **Pharmacy → Linked clinics**. On **${CLINIC.org}** (Waiting) press **Accept** and confirm.`,
      [`**${CLINIC.org}** reads **Linked**, with **End link**.`])
    signIn(PHARMA.email)
    openLinkedClinics()
    cy.contains('#lcLinks tr', CLINIC.org, { timeout: 15000 }).should('contain.text', 'Waiting').find('.lc-accept').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.contains('#lcLinks tr', CLINIC.org, { timeout: 15000 }).should('contain.text', 'Linked')
    g.snap(a1, 'accepted')

    const a2 = g.act('Sign in as the clinic owner. Open **Linked pharmacy**, then the **Clinic home**.',
      [`The status reads **Linked to ${PHARMA.org}**.`, 'On the Clinic home, **Link your pharmacy** is ticked.'])
    signIn(CLINIC.email)
    openLinkedPharmacy()
    cy.get('#plStatus', { timeout: 15000 }).should('contain.text', 'Linked').and('contain.text', PHARMA.org)
    cy.visit('/clinicHome')
    cy.get('#chSetup [data-step="pharmacy"]', { timeout: 15000 }).should('have.attr', 'data-done', 'true')
    g.snap(a2, 'clinic-linked')
  })

  // ── L5 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('L5', 'One pharmacy at a time', () => {
    g.testCase('L5', 'While linked, the clinic cannot ask a second pharmacy', {
      tenant: CLINIC.email, role: 'Clinic owner',
      purpose: 'One active pharmacy per clinic, today (the user, 2026-10-15). The screen offers no search while linked; the server refuses a second request too (automated: hc-1c-link-api).',
      prereq: ['L4 has run.'], data: ['—'], rollback: 'Nothing is saved.',
    })
    const a1 = g.act('Open **Linked pharmacy**.', ['There is no search box — only the linked pharmacy and **End link**.'])
    signIn(CLINIC.email)
    openLinkedPharmacy()
    cy.get('#plSearch').should('not.be.visible')
    cy.get('#plEnd').should('be.visible')
    g.snap(a1, 'one-only')
  })

  // ── L6 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('L6', 'The pharmacy ends the link', () => {
    g.testCase('L6', 'Either side can end the link, with a reason — both sides read Ended', {
      tenant: `${PHARMA.email}, then ${CLINIC.email}`, role: 'Pharmacy owner, then clinic owner',
      purpose: 'Ending stops new prescriptions; nothing already sent is deleted. Recorded in both businesses\' audit trails.',
      prereq: ['L4 has run.'], data: ['Reason **guide cleanup**'],
      rollback: 'This IS the clean-up of L3/L4. The three tenants are suspended after the run.',
    })
    const a1 = g.act(`As the pharmacy owner, on **${CLINIC.org}** press **End link**, type the reason **guide cleanup** and confirm.`,
      [`**${CLINIC.org}** reads **Ended**.`])
    signIn(PHARMA.email)
    openLinkedClinics()
    cy.contains('#lcLinks tr', CLINIC.org, { timeout: 15000 }).find('.lc-end').click()
    cy.get('.uiC-input').type('guide cleanup')
    cy.get('[data-ui-confirm="ok"]').click()
    cy.contains('#lcLinks tr', CLINIC.org, { timeout: 15000 }).should('contain.text', 'Ended')
    g.snap(a1, 'ended')

    const a2 = g.act('Sign in as the clinic owner and open **Linked pharmacy**.',
      ['The status reads **No pharmacy linked**, and the search is back.'])
    signIn(CLINIC.email)
    openLinkedPharmacy()
    cy.get('#plStatus', { timeout: 15000 }).should('contain.text', 'No pharmacy linked')
    cy.get('#plSearch').should('be.visible')
    g.snap(a2, 'clinic-unlinked')
  })
})
