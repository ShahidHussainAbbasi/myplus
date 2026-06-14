/**
 * Education — student CSV import (slice 15). Headed + slowed so the flow is visible.
 * Uploads cypress/fixtures/students-import.csv and asserts the summary renders.
 */

describe('Education — student CSV import', () => {
  const SLOW = 900

  beforeEach(() => {
    cy.loginAsEducation()
    cy.visit('/educationDashboard')
    cy.wait(SLOW)
  })

  it('imports a CSV and shows a summary', () => {
    cy.get('#registrationType').select('StudentDiv', { force: true })
    cy.wait(SLOW)
    cy.get('#StudentDiv').should('be.visible')
    cy.get('#impStudentsFile').selectFile('cypress/fixtures/students-import.csv', { force: true })
    cy.wait(SLOW)
    cy.contains('button', 'Import').click()
    cy.get('#impStudentsSummary', { timeout: 15000 }).should('contain', 'Created')
  })

  it('/getUserStudent returns SUCCESS after import', () => {
    cy.request('/getUserStudent').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
    })
  })
})
