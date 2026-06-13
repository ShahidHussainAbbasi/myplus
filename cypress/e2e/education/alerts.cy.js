/**
 * Education — alerts module (slice 16). Headed + slowed so it's visible.
 * Public Alerts: import contacts CSV -> list (DataTable) -> compose -> send (real email to admin recipients).
 * System Alerts: create via form -> list (DataTable).
 *
 * Assertions wait on the real network responses (cy.intercept) and the rendered rows rather than
 * on alert() timing, which races the jQuery success callback against a fixed timer.
 */

describe('Education — alerts module', () => {
  const SLOW = 900

  beforeEach(() => {
    cy.loginAsEducation()
    cy.visit('/educationDashboard')
    cy.wait(SLOW)
  })

  it('public alerts: import contacts, list, and send', () => {
    cy.intercept('POST', '**/importCSV').as('importCSV')
    cy.intercept('POST', '**/sendPA').as('sendPA')

    cy.get('#communicationType').select('PADiv', { force: true })
    cy.wait(SLOW)
    cy.get('#PADiv').should('be.visible')

    cy.get('#csvFile').selectFile('cypress/fixtures/contacts-import.csv', { force: true })
    cy.get('#paBtn').click()
    cy.wait('@importCSV').its('response.statusCode').should('eq', 200)

    // Contacts list renders through the shared DataTable path.
    cy.get('#tablePA tbody tr', { timeout: 10000 }).should('have.length.greaterThan', 0)

    cy.get('#pah').clear().type('Test Notice')
    cy.get('#pam').clear().type('This is a test public alert from the SMS.')
    cy.get('#sendPA').click()
    // The send response is synchronous over SMTP, so give it room and assert the server's own tally.
    cy.wait('@sendPA', { timeout: 30000 }).then((i) => {
      expect(i.response.statusCode).to.eq(200)
      expect(JSON.stringify(i.response.body)).to.match(/Sent to/i)
    })
  })

  it('system alerts: list endpoint responds', () => {
    cy.request('/getUserAlerts').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
    })
  })

  it('system alert can be created via the form', () => {
    cy.intercept('POST', '**/addAlerts').as('addAlerts')

    cy.get('#communicationType').select('AlertsDiv', { force: true })
    cy.wait(SLOW)
    cy.get('#AlertsDiv').should('be.visible')
    cy.get('#acdd').select(['Students'], { force: true })
    cy.get('#atdd').select(['Notice Board'], { force: true })
    cy.get('#adcdd').select(['Email'], { force: true })
    cy.get('#adpdd').select(['Daily'], { force: true })
    cy.get('#adtdd').select('Manual', { force: true })
    cy.get('#ah').clear().type('Holiday Notice')
    cy.get('#am').clear().type('School closed Friday.')

    cy.get('#addAlerts').click()
    cy.wait('@addAlerts').its('response.statusCode').should('eq', 200)
    // Saved alert appears in the DataTable-rendered list.
    cy.get('#tableAlerts tbody tr', { timeout: 10000 }).should('have.length.greaterThan', 0)
  })
})
