/**
 * Education dashboard — page loads and getDashboardData returns stats.
 */

describe('Education — dashboard', () => {
  beforeEach(() => {
    cy.loginAsEducation()
  })

  it('educationDashboard page renders', () => {
    cy.visit('/educationDashboard')
    cy.get('#container').should('exist')
    cy.get('#content').should('exist')
  })

  it('getDashboardData returns SUCCESS with stats object', () => {
    cy.request('/getDashboardData').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.eq('SUCCESS')
      expect(res.body.object).to.have.property('allStudent')
      expect(res.body.object).to.have.property('freshStudent')
    })
  })
})
