/**
 * HC-1a — Healthcare: a Hospital / Clinic tenant and a Pharmacy & Pharmaceutical tenant, as REAL step-by-step manual
 * cases for the Test Book. Written BEFORE the build (the user's rule, 2026-10-15): every action is PERFORMED on the
 * screen, ASSERTED and PHOTOGRAPHED (support/guide-capture.js), and each case ends with its clean-up. Design:
 * microservices/docs/slices/hc-1a-healthcare-business-types.md.
 *
 *   C1  the operator creates a Hospital / Clinic tenant (Healthcare → Hospital / Clinic) from the console
 *   C2  its owner sets a password from the e-mail and lands on the CLINIC HOME — Front desk, Doctor, Administration;
 *       no Pharmacy, no Stock
 *   C3  each tile opens its clinic screen (Reception · My queue · the overview)
 *   C4  a Pharmacy & Pharmaceutical tenant lands on the pharmacy dashboard — and its Pharmacy menu has no
 *       "Clinic reception"
 *   C5  the same e-mail cannot own a second tenant (one e-mail = one tenant owner, today)
 *
 * EXPECTED RED on the build it was written against: the console has no Healthcare picker (#provProductLine), there is
 * no /clinicHome, and the CLINIC type does not exist.
 *
 * TENANTS: each run creates its own two tenants (names carry the run id, owner e-mails end in .test — never
 * delivered; set-password links are read from Mailpit). Clean-up SUSPENDS them on screen (tenants are never deleted).
 *
 * Run headed (one case: --env '{"guideOnly":"C2"}'):
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/docs/hc-1a-healthcare-guide-screens.cy.js --config trashAssetsBeforeRuns=false
 */
import { guideCapture } from '../../support/guide-capture'

const g = guideCapture({ outDir: 'cypress/guide-out/hc-1a', section: 'Healthcare tenants', keepShots: true })
const RUN = String(Date.now()).slice(-6)
const MAILPIT = 'http://localhost:8025'
const PW = 'Kiwi#Lamp58'
const CLINIC = { org: `HC1A Clinic ${RUN}`, email: `hc1a.clinic.${RUN}@myplus.test`, first: 'Tahseen', last: `Test ${RUN}` }
const PHARMA = { org: `HC1A Pharmacy ${RUN}`, email: `hc1a.pharma.${RUN}@myplus.test`, first: 'Tahseen', last: `Test ${RUN}` }
const made = []   // org names this run created — after() suspends any a failed case left ACTIVE

const openConsole = () => {
  cy.loginAsOperator()
  cy.visit('/platformDashboard')   // the console PAGE (/platform/organizations is its JSON list)
  cy.get('#platProvisionBtn', { timeout: 20000 }).should('be.visible')
}
/** The console's New tenant form, filled for a Healthcare business type. */
const provision = (t, businessTypeLabel) => {
  cy.get('#platProvisionBtn').click()
  cy.get('#platProvision').should('be.visible')
  cy.get('#provOrgName').clear().type(t.org)
  cy.get('#provEmail').clear().type(t.email)
  cy.get('#provFirstName').clear().type(t.first)
  cy.get('#provLastName').clear().type(t.last)
  cy.get('#provProductLine').select('Healthcare')
  cy.get('#provBusinessType').select(businessTypeLabel)
  cy.get('#provPlan').select('PRO')
  cy.intercept('POST', '**/platform/provisionTenant*').as('provision')
  cy.get('#provSubmit').click()
}
/** After a successful Create the console shows a "Tenant provisioned" notice; it must be dismissed (OK). */
const dismissNotice = () => {
  cy.get('[data-ui-confirm="ok"]', { timeout: 15000 }).should('be.visible').click()
  cy.get('[data-ui-confirm="ok"]').should('not.exist')
}
const tenantRow = (orgName) => {
  cy.get('#platSearch', { timeout: 20000 }).clear().type(orgName)
  return cy.contains('[data-testid="tenant-row"]', orgName, { timeout: 20000 })
}
/** The newest set-password link e-mailed to `email`, from Mailpit. */
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
}
/** Clean-up on screen: tenant detail → status SUSPENDED → reason → confirm. */
const suspendOnScreen = (orgName) => {
  openConsole()
  tenantRow(orgName).click()
  cy.get('#platStatusSelect', { timeout: 20000 }).select('SUSPENDED')
  cy.get('#platStatusSave').click()
  cy.get('.uiC-input').type('guide cleanup')
  cy.get('[data-ui-confirm="ok"]').click()
  cy.get('#platStatusSelect', { timeout: 20000 }).should('have.value', 'SUSPENDED')
}

describe('Test Book — HC-1a Healthcare tenants (captured)', () => {
  beforeEach(() => cy.viewport(1366, 900))
  afterEach(g.write)

  after(() => {
    // A case that failed half-way must not leave its tenant ACTIVE.
    made.forEach((orgName) => {
      cy.loginAsOperator()
      cy.request(`/platform/organizations?page=0&size=20&q=${encodeURIComponent(orgName)}`).then((r) => {
        const d = (r.body && (r.body.data || r.body.object || r.body)) || {}
        ;(d.rows || []).filter((o) => o && o.name === orgName && o.status === 'ACTIVE').forEach((o) =>
          cy.request({ method: 'POST', url: '/platform/status', form: true, failOnStatusCode: false,
            body: { organizationId: o.id, status: 'SUSPENDED', reason: 'guide cleanup' } }))
      })
    })
  })

  // ── C1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('C1', 'The operator creates a Hospital / Clinic tenant', () => {
    g.testCase('C1', 'The operator creates a Hospital / Clinic tenant (Healthcare → Hospital / Clinic)', {
      tenant: 'admin@myplus.com', role: 'Platform operator',
      purpose: 'A clinic and a pharmacy are now two separate businesses (the combined clinic-inside-a-pharmacy tenant is dropped). The operator picks the product line Healthcare and the business type Hospital / Clinic.',
      prereq: ['Signed in as admin@myplus.com.', 'Mailpit open at http://localhost:8025 (where development e-mail lands).'],
      data: [`Business name **${CLINIC.org}**`, `Owner **${CLINIC.first} ${CLINIC.last}**, e-mail **${CLINIC.email}**`, 'Plan **PRO**'],
      rollback: 'Cleanup suspends the tenant on screen. Tenants are never deleted.',
    })
    const a1 = g.act('Open **Tenants** and press **New tenant**. Type the business name, owner e-mail, first and last name. Choose **Product line: Healthcare**, **Business type: Hospital / Clinic**, plan **PRO**, and press **Create**.',
      ['A notice says **Tenant provisioned** (a set-password e-mail went to the owner). Press **OK**.'])
    openConsole()
    made.push(CLINIC.org)
    provision(CLINIC, 'Hospital / Clinic')
    cy.wait('@provision').its('response.statusCode').should('eq', 200)
    cy.contains('Tenant provisioned').should('be.visible')
    g.snap(a1, 'created')
    dismissNotice()

    const a2 = g.act(`Type **${CLINIC.org}** in the tenant search.`, ['The row shows the business type **Hospital / Clinic**.'])
    tenantRow(CLINIC.org).find('[data-testid="tenant-shape"]').should('contain.text', 'Hospital / Clinic')
    g.snap(a2, 'listed')

    const c1 = g.act('Leave the tenant ACTIVE for C2 and C3 (they use it). It is suspended at the end of C3.', ['—'], { cleanup: true })
    g.snap(c1, 'kept-for-c2')
  })

  // ── C2 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('C2', 'The clinic owner lands on the Clinic home', () => {
    g.testCase('C2', 'The clinic owner sets a password and lands on the Clinic home — no Pharmacy, no Stock', {
      tenant: CLINIC.email, role: 'Owner of the Hospital / Clinic tenant created in C1',
      purpose: 'A clinic has its own landing dashboard (the user, 2026-10-15) so it can grow clinic-only options. Its tiles are the clinic workspaces; the Pharmacy and Stock tiles belong to a pharmacy and are never shown to a clinic.',
      prereq: ['C1 has run (the tenant exists).'],
      data: [`Owner **${CLINIC.email}**`, `Password **${PW}**`],
      rollback: 'None here — the tenant is suspended at the end of C3.',
    })
    const a1 = g.act('Open the set-password e-mail in Mailpit, follow its link, type the password twice and press **Update Password**.',
      ['The login page opens, saying the password was reset.'])
    setPasswordLink(CLINIC.email).then(choosePassword)
    g.snap(a1, 'password-set')

    const a2 = g.act(`Sign in as **${CLINIC.email}**.`,
      ['The **Clinic home** opens (address **/clinicHome**).', 'It shows the tiles **Front desk**, **Doctor** and **Administration**.',
        'There is **no Pharmacy** tile and **no Stock** tile.'])
    signIn(CLINIC.email)
    cy.location('pathname', { timeout: 20000 }).should('eq', '/clinicHome')
    cy.get('#chTiles [data-ws="frontdesk"]').should('be.visible')
    cy.get('#chTiles [data-ws="doctor"]').should('be.visible')
    cy.get('#chTiles [data-ws="clinic_admin"]').should('be.visible')   // HC-1b: the clinic's own Administration tile
    cy.get('#chTiles [data-ws="pharmacy"]').should('not.exist')
    cy.get('#chTiles [data-ws="store"]').should('not.exist')
    g.snap(a2, 'clinic-home')

    const c1 = g.act('Nothing to undo — C3 continues with this tenant.', ['—'], { cleanup: true })
    g.snap(c1, 'continue')
  })

  // ── C3 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('C3', 'Each Clinic home tile opens its clinic screen', () => {
    g.testCase('C3', 'Each Clinic home tile opens its clinic screen', {
      tenant: CLINIC.email, role: 'Clinic owner',
      purpose: 'The clinic runs the five screens already built (Reception, My queue, Today\'s queue, Doctors, Clinic settings); the home is the way in.',
      prereq: ['C2 has run (the owner has a password).'],
      data: ['—'],
      rollback: 'Cleanup suspends the clinic tenant on screen.',
    })
    const a1 = g.act('On the Clinic home, press **Front desk**.', ['The clinic screen opens on **Reception**.'])
    signIn(CLINIC.email)
    cy.get('#chTiles [data-ws="frontdesk"]', { timeout: 20000 }).click()
    cy.location('pathname', { timeout: 20000 }).should('eq', '/clinicDashboard')
    cy.get('#ReceptionDiv').should('be.visible')
    g.snap(a1, 'reception')

    const a2 = g.act('Go back to the Clinic home and press **Doctor**.', ['The clinic screen opens on **My queue**.'])
    cy.visit('/clinicHome')
    cy.get('#chTiles [data-ws="doctor"]').click()
    cy.get('#MyQueueDiv', { timeout: 20000 }).should('be.visible')
    g.snap(a2, 'my-queue')

    const a3 = g.act('Go back and press **Administration**.', ["The clinic screen opens on **Team** — the clinic's own admin work (HC-1b), not the shop's overview."])
    cy.visit('/clinicHome')
    cy.get('#chTiles [data-ws="clinic_admin"]').click()
    cy.location('pathname', { timeout: 20000 }).should('eq', '/clinicDashboard')
    cy.get('#TeamDiv', { timeout: 20000 }).should('be.visible')
    g.snap(a3, 'administration')

    const c1 = g.act(`As the operator, open **${CLINIC.org}** in Tenants, set the status to **SUSPENDED**, give the reason **guide cleanup** and confirm.`,
      ['The status reads **SUSPENDED**; the tenant is kept, nothing is deleted.'], { cleanup: true })
    suspendOnScreen(CLINIC.org)
    g.snap(c1, 'suspended')
  })

  // ── C4 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('C4', 'A Pharmacy & Pharmaceutical tenant has no clinic', () => {
    g.testCase('C4', 'A Pharmacy & Pharmaceutical tenant lands on the pharmacy dashboard — without "Clinic reception"', {
      tenant: `admin@myplus.com, then ${PHARMA.email}`, role: 'Operator, then the pharmacy owner',
      purpose: 'The pharmacy is today\'s pharmacy, unchanged — except that the clinic is no longer inside it: its Pharmacy menu has no "Clinic reception".',
      prereq: ['Signed in as admin@myplus.com.'],
      data: [`Business name **${PHARMA.org}**`, `Owner e-mail **${PHARMA.email}**`],
      rollback: 'Cleanup suspends the tenant on screen.',
    })
    const a1 = g.act('In **Tenants → New tenant**, choose **Healthcare → Pharmacy & Pharmaceutical**, fill the owner and press **Create**.',
      ['A notice says **Tenant provisioned**; press **OK**.', 'Its row shows **Pharmacy & Pharmaceutical**.'])
    openConsole()
    made.push(PHARMA.org)
    provision(PHARMA, 'Pharmacy & Pharmaceutical')
    cy.wait('@provision').its('response.statusCode').should('eq', 200)
    dismissNotice()
    tenantRow(PHARMA.org).find('[data-testid="tenant-shape"]').should('contain.text', 'Pharmacy & Pharmaceutical')
    g.snap(a1, 'created')

    const a2 = g.act('The owner sets a password from the e-mail and signs in. Open the **Pharmacy** menu.',
      ['The pharmacy dashboard opens.', 'The Pharmacy menu has **no "Clinic reception"** entry.'])
    setPasswordLink(PHARMA.email).then(choosePassword)
    signIn(PHARMA.email)
    cy.location('pathname', { timeout: 20000 }).should('eq', '/businessDashboard')
    cy.get('#navClinic').should('not.exist')
    g.snap(a2, 'pharmacy-dashboard')

    const c1 = g.act(`As the operator, suspend **${PHARMA.org}** (status **SUSPENDED**, reason **guide cleanup**).`,
      ['The status reads **SUSPENDED**.'], { cleanup: true })
    suspendOnScreen(PHARMA.org)
    g.snap(c1, 'suspended')
  })

  // ── C5 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('C5', 'One e-mail owns one tenant', () => {
    g.testCase('C5', 'The same e-mail cannot own a second tenant — today', {
      tenant: 'admin@myplus.com', role: 'Platform operator',
      purpose: 'One e-mail = one tenant owner (the user, 2026-10-15). Dr Tahseen registers her clinic and her pharmacy with two e-mails; one login across tenants is a later slice.',
      prereq: ['C1 has run (its owner e-mail exists).'],
      data: [`A second tenant **HC1A Second ${RUN}** with the clinic owner's e-mail **${CLINIC.email}**`],
      rollback: 'Nothing is created — the refusal writes nothing.',
    })
    const a1 = g.act(`In **Tenants → New tenant**, choose **Healthcare → Pharmacy & Pharmaceutical** and use the e-mail **${CLINIC.email}** again. Press **Create**.`,
      ['The form refuses: the e-mail already owns a tenant. No second tenant appears.'])
    openConsole()
    provision({ ...CLINIC, org: `HC1A Second ${RUN}` }, 'Pharmacy & Pharmaceutical')
    cy.wait('@provision')
    cy.get('#platProvision').should('contain.text', 'already')
    g.snap(a1, 'refused')

    const c1 = g.act('Nothing to undo — the refusal created nothing.', ['—'], { cleanup: true })
    g.snap(c1, 'nothing-created')
  })
})
