/**
 * HMS test page — "Before you start", recorded on the real screens (release readiness 2026-10-11).
 *
 * What a manual tester does once before any HMS case, as the automation does it:
 *   SET-01  the platform operator grants the Clinic capability to the pharmacy's organisation
 *   SET-02  the owner switches the Clinic on in Settings → Configuration
 *   SET-03  the clinic opens, and its Doctors screen lists the doctors (registered by the owner — H2)
 *
 * Cleanup: the owner's Clinic switch is reset in after(), as every HMS spec does. The operator's grant is left
 * ACTIVE, as every HMS spec leaves it (it only ALLOWS the owner to switch the clinic on).
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-setup-before-you-start.cy.js"
 */
describe('HMS — before you start: switch the clinic on', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const PW = 'Demo@2025!'
  const run = String(Date.now()).slice(-7)
  const relogin = () => cy.loginAs(OWNER, PW, '/getBusinessDashboardStats', `hms-setup-${run}`)

  after(() => {
    relogin()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  it('SET-01 the platform operator grants the Clinic capability to the organisation', () => {
    cy.loginAsOperator()
    cy.intercept('POST', '**/platform/entitlement').as('grant')
    cy.visit('/platformDashboard')
    cy.get('#platSearch').type('Owner Pharma')
    cy.get('[data-testid="tenant-row"]').contains('Owner Pharma').click()
    cy.get('#platDetailBody .plat-cap[data-cap="clinic"]').should('be.visible').scrollIntoView()
    cy.screenshot('hms-setup/SET-01-1-operator-tenant-clinic', { capture: 'viewport' })
    cy.get('#platDetailBody .plat-cap[data-cap="clinic"]').then(($cap) => {
      const $grant = $cap.find('.js-grant:visible')
      if ($grant.length) {
        cy.wrap($grant).click()
        cy.get('.uiC-input').type('HMS manual test')
        cy.get('[data-ui-confirm="ok"]').click()
        // the grant must LAND: the request answers SUCCESS, then the panel re-renders (it shows "Loading..." first)
        cy.wait('@grant').its('response.statusCode').should('eq', 200)
      }
    })
    cy.get('#platDetailBody').should('not.contain', 'Loading')
    // granted: the card's badge reads Entitled (every card shows BOTH Grant and Revoke, so the buttons prove nothing).
    // The panel re-renders after the grant, so the card is looked up again and scrolled to after the badge appears.
    cy.get('#platDetailBody .plat-cap[data-cap="clinic"] .plat-badge--ok').should('be.visible')
    cy.get('#platDetailBody .plat-cap[data-cap="clinic"] .plat-badge--revoked').should('not.exist')
    cy.get('#platDetailBody .plat-cap[data-cap="clinic"]').scrollIntoView({ offset: { top: -200, left: 0 } })
      .then(($c) => $c.css('outline', '3px solid #d97706'))
    cy.screenshot('hms-setup/SET-01-2-operator-clinic-granted', { capture: 'viewport' })
  })

  it('SET-02 the owner switches the Clinic on in Settings → Configuration', () => {
    relogin()
    cy.intercept('POST', '**/saveBusinessConfig').as('save')
    cy.visit('/businessDashboard')
    cy.get('#navConfiguration').click({ force: true })
    cy.get('#businessConfigBody [data-key="org.cap.clinic"]').should('exist').scrollIntoView()
    cy.screenshot('hms-setup/SET-02-1-owner-configuration', { capture: 'viewport' })
    cy.get('#businessConfigBody [data-key="org.cap.clinic"]').then(($el) => {
      if (!$el.prop('checked')) {
        cy.wrap($el).check({ force: true })
        cy.wait('@save').its('response.body').then((b) => expect(JSON.stringify(b)).to.match(/success|SUCCESS|true/))
      }
    })
    cy.get('#businessConfigBody [data-key="org.cap.clinic"]').should('be.checked')
      .closest('div').then(($d) => $d.css('outline', '3px solid #d97706'))
    cy.screenshot('hms-setup/SET-02-2-owner-clinic-on', { capture: 'viewport' })
  })

  it('SET-03 the clinic opens, and Doctors lists the doctors the owner registered', () => {
    relogin()
    cy.visit('/clinicDashboard')
    cy.get('#clinNavReception').should('be.visible')
    cy.screenshot('hms-setup/SET-03-1-clinic-open', { capture: 'viewport' })
    cy.get('#clinNavDoctors').click()
    cy.get('#clinDoctorBody tr').should('have.length.greaterThan', 0)
    cy.screenshot('hms-setup/SET-03-2-doctors', { capture: 'viewport' })
  })
})
