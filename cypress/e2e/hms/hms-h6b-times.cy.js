/**
 * HMS H6 phase B — the clinic and pharmacy screens show every time in the VIEWER's zone (docs/hms-phase1-design.md
 * §4j: the Salesforce / Shopify / Stripe / Odoo model — the instant travels as UTC, one formatter shows it locally).
 *
 * The services return zone-less UTC (verified: a 10:36 PKT prescription arrived as 05:36). Each case reads the RAW
 * value from the API, computes the expected local text here with Intl (independently of the page's formatter), and
 * compares the screen. Run under Chrome's time-zone emulation twice — Asia/Karachi and Pacific/Kiritimati (UTC+14) —
 * so a formatter that only "adds 5 hours" fails the second.
 *
 * Roles: owner.pharma@ (clinic on; sees the patient list and every prescription).
 * Server state: nothing written; the zone emulation is reset after each case.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-h6b-times.cy.js"
 */
import { localText } from '../../support/time'
describe('HMS H6 phase B — clinic and pharmacy times in the viewer\'s zone', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const PW = 'Demo@2025!'
  const run = String(Date.now()).slice(-7)
  const relogin = () => cy.loginAs(OWNER, PW, '/getBusinessDashboardStats', `hms-h6b-${run}`)


  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms h6b gate')
    relogin()
    cy.setCapability('clinic', true)
  })
  afterEach(() => { cy.emulateZone('') })   // back to the machine's zone
  after(() => {
    relogin()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  ;['Asia/Karachi', 'Pacific/Kiritimati'].forEach((zone, i) => {
    it(`T-0${i + 1} the pharmacy's prescription times read in ${zone}`, () => {
      relogin()
      cy.emulateZone(zone)
      cy.intercept('GET', '**/searchPrescriptions*').as('search')
      cy.visit('/businessDashboard')
      cy.window().its('viewerZone').then((vz) => expect(vz(), 'the page sees the emulated zone').to.eq(zone))
      cy.get('#snavPharmacy > .snav-btn').click()
      cy.get('#snavPharmacy a[onclick^="showPrescriptions"]').click()
      cy.wait('@search').then(({ response }) => {
        const first = (response.body.data.items || [])[0]
        expect(first, 'at least one prescription to read').to.exist
        const want = localText(first.createdAt, zone)
        const raw = String(first.createdAt).replace('T', ' ').substring(0, 16)
        cy.screenshot(`hms-h6b/T-0${i + 1}-1-prescriptions-open-${zone.replace('/', '-')}`, { capture: 'viewport' })
        cy.get(`#prescriptionBody tr[data-rx-id="${first.id}"] td`).eq(4).should('have.text', want)
          .then(($td) => $td.css('outline', '3px solid #d97706'))
        // the proof it is converted, not sliced: the UTC text is not what the viewer reads
        expect(want, 'converted differs from the raw UTC slice').to.not.eq(raw)
      })
      cy.screenshot(`hms-h6b/T-0${i + 1}-2-pharmacy-${zone.replace('/', '-')}`, { capture: 'viewport' })
    })
  })

  ;['Asia/Karachi', 'Pacific/Kiritimati'].forEach((zone, i) => {
    it(`T-0${i + 3} the clinic's patient registration times read in ${zone}`, () => {
      relogin()
      cy.emulateZone(zone)
      cy.intercept('GET', '**/clinic/patients*').as('patients')
      cy.visit('/clinicDashboard')
      cy.wait('@patients').then(({ response }) => {
        const first = (response.body.data || [])[0]
        expect(first, 'at least one patient to read').to.exist
        cy.get(`#clinPatBody tr[data-patient-id="${first.id}"] td`).eq(3).should('have.text', localText(first.createdAt, zone))
          .then(($td) => { $td.css('outline', '3px solid #d97706'); $td[0].scrollIntoView({ block: 'center' }) })
      })
      cy.screenshot(`hms-h6b/T-0${i + 3}-1-reception-${zone.replace('/', '-')}`, { capture: 'viewport' })
    })
  })

  it('T-05 a date (birthday, due date) is never shifted, in any zone', () => {
    relogin()
    cy.emulateZone('Pacific/Kiritimati')
    cy.visit('/clinicDashboard')
    cy.window().then((w) => {
      expect(w.fmtDate('2026-10-11')).to.eq('2026-10-11')
      expect(w.fmtDateTime('2026-10-11')).to.eq('2026-10-11')
      expect(w.fmtDateTime('2026-10-11T05:36:15Z')).to.eq('2026-10-11 19:36')
      expect(w.typedToServer('2026-10-11T09:00')).to.eq('2026-10-10T19:00:00Z')
    })
  })
})
