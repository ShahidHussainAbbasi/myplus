/**
 * Landing page and public page tests
 */

describe('Landing Page (/)', () => {
  it('should load the landing page without redirecting to login', () => {
    cy.visit('/')
    cy.url().should('not.include', '/login')
  })

  it('should display the main navigation or call-to-action', () => {
    cy.visit('/')
    cy.get('body').should('be.visible')
    // Page content exists
    cy.get('body').invoke('text').should('not.be.empty')
  })
})

describe('Registration Page', () => {
  it('should load the registration page', () => {
    cy.visit('/registration.html', { failOnStatusCode: false })
    cy.get('body').should('be.visible')
  })

  it('should have a form with required fields', () => {
    cy.visit('/registration.html', { failOnStatusCode: false })
    // Check for common registration fields
    cy.get('input[name="email"], input[type="email"]').should('exist')
    cy.get('input[name="password"], input[type="password"]').should('exist')
  })
})

describe('Error Pages', () => {
  it('should return 302 or redirect for protected route when not logged in', () => {
    cy.clearCookies()
    cy.request({ url: '/businessDashboard', followRedirect: false, failOnStatusCode: false }).then((res) => {
      expect([302, 401, 403]).to.include(res.status)
    })
  })

  it('should handle bad user page', () => {
    cy.visit('/badUser', { failOnStatusCode: false })
    cy.get('body').should('be.visible')
  })
})
