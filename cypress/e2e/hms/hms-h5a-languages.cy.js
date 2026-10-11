/**
 * HMS H5a — the clinic and pharmacy screens in every language of the platform (client ruling L-5), and the Doctors
 * table fits a 1280 px screen (H2 finding).
 *
 *   L-01..05  for ar, es, fr, hi, ur: the clinic screen shows its OWN words (not English, not a raw message key),
 *             and Arabic / Urdu read right-to-left.
 *   L-06      the Doctors table does not scroll sideways at 1280 px.
 *
 * Scope note: H5a translates the PAGE keys. Sentences the clinic script builds (messages, row buttons) are H5b; this
 * gate checks only page text so it cannot pass or fail on them.
 *
 * Roles: owner.pharma@ (clinic on). Server state put back in after(): the language → English (a session setting);
 * clinic switch reset.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-h5a-languages.cy.js"
 */
describe('HMS H5a — the clinic in every language; the Doctors table fits', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const PW = 'Demo@2025!'
  const run = String(Date.now()).slice(-7)
  const relogin = (phase = 'on') => cy.loginAs(OWNER, PW, '/getBusinessDashboardStats', `hms-h5a-${run}-${phase}`)

  // page text that must change with the language: the side menu and the reception card's title / button
  const LANGS = [
    { tag: 'ar', dir: 'rtl', reception: 'الاستقبال', myQueue: 'طابوري', doctors: 'الأطباء', find: 'بحث' },
    { tag: 'es', dir: 'ltr', reception: 'Recepción', myQueue: 'Mi cola', doctors: 'Médicos', find: 'Buscar' },
    { tag: 'fr', dir: 'ltr', reception: 'Accueil', myQueue: 'Ma file', doctors: 'Médecins', find: 'Rechercher' },
    { tag: 'hi', dir: 'ltr', reception: 'रिसेप्शन', myQueue: 'मेरी कतार', doctors: 'डॉक्टर', find: 'खोजें' },
    { tag: 'ur', dir: 'rtl', reception: 'استقبالیہ', myQueue: 'میری قطار', doctors: 'ڈاکٹرز', find: 'تلاش کریں' },
  ]

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms h5a gate')
    relogin('pre')
    cy.setCapability('clinic', true)
  })

  after(() => {
    relogin()
    cy.visit('/clinicDashboard?lang=en')
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  LANGS.forEach((l, i) => {
    it(`L-0${i + 1} [L-5] ${l.tag}: the clinic screen speaks ${l.tag}${l.dir === 'rtl' ? ', right-to-left' : ''}; no raw keys`, () => {
      relogin()
      cy.visit(`/clinicDashboard?lang=${l.tag}`)
      cy.get('html').should('have.attr', 'lang', l.tag).and('have.attr', 'dir', l.dir)
      cy.get('#clinNavReception').should('contain', l.reception)
      cy.get('#clinNavDoctors').should('contain', l.doctors)
      cy.get('#clinNavMyQueue').should('contain', l.myQueue)   // the owner is a doctor here: revealed by /doctors/me
      cy.get('#ReceptionDiv').should('contain', l.find)
      // no message key leaked through untranslated (Spring renders a missing one as ??key_locale??)
      cy.get('body').invoke('text').should('not.match', /\?\?ui\.[A-Za-z]+/)
      cy.screenshot(`hms-h5a/L-0${i + 1}-clinic-${l.tag}`, { capture: 'viewport' })
    })
  })

  // ── H5b / H5c: the words the scripts build, and the .clin-link collision ──────────────────────────────

  it("L-07 [L-5 · H5b] the clinic script's own words in Urdu: the Doctors table's buttons and the status words", () => {
    relogin()
    cy.visit('/clinicDashboard?lang=ur')
    cy.get('#clinNavDoctors').click()
    cy.get('#clinDoctorBody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)
    cy.get('#clinDoctorBody').should('contain', 'آج کے لیے مقرر کریں')   // "Set today"
      .and('not.contain', 'Set today')
    cy.get('#clinNavQueue').click()
    cy.get('#clinQueueUpdated', { timeout: 20000 }).should('contain', 'تازہ کاری')   // "Updated …"
    cy.screenshot('hms-h5a/L-07-1-clinic-script-urdu', { capture: 'viewport' })
  })

  it("L-08 [L-5 · H5c] the pharmacy's HMS words in Urdu: History on every prescription row", () => {
    relogin()
    cy.intercept('GET', '**/searchPrescriptions*').as('search')
    cy.visit('/businessDashboard?lang=ur')
    cy.get('#snavPharmacy > .snav-btn').click()
    cy.get('#snavPharmacy a[onclick^="showPrescriptions"]').click()
    cy.wait('@search')
    cy.get('#prescriptionBody tr', { timeout: 20000 }).should('have.length.greaterThan', 0)
    cy.get('#prescriptionBody .rx-history-link').first().should('contain', 'تاریخچہ').and('not.contain', 'History')
    cy.screenshot('hms-h5a/L-08-1-pharmacy-urdu', { capture: 'viewport' })
  })

  it('L-09 [H5b bug] a doctor\'s "Link login" never fires the reception\'s patient → customer link', () => {
    // both handlers listened on .clin-link: "Link login" also POSTed /clinic/patients/<doctor id>/link
    relogin()
    let patientLinks = 0
    cy.intercept('POST', '**/clinic/patients/*/link', (req) => { patientLinks++; req.reply({ statusCode: 418, body: {} }) }).as('patientLink')
    cy.visit('/clinicDashboard?lang=en')
    cy.get('#clinNavDoctors').click()
    cy.get('#clinDoctorBody .clin-login-link', { timeout: 30000 }).first().click()
    cy.get('#clinDoctorBody .clin-link-user').should('exist')   // the login picker opened (its own handler)
    cy.wait(1000).then(() => expect(patientLinks, 'patient-link requests fired by "Link login"').to.eq(0))
  })

  it('L-06 the Doctors table does not scroll sideways at 1280 px', () => {
    relogin()
    cy.viewport(1280, 800)
    cy.visit('/clinicDashboard?lang=en')
    cy.get('#clinNavDoctors').click()
    cy.get('#clinDoctorBody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)
    cy.get('#DoctorsDiv .table-responsive').first().then(($w) => {
      const w = $w[0]
      expect(w.scrollWidth, `table ${w.scrollWidth}px within its ${w.clientWidth}px box`).to.be.at.most(w.clientWidth + 1)
    })
    cy.screenshot('hms-h5a/L-06-1-doctors-fit', { capture: 'viewport' })
  })
})
