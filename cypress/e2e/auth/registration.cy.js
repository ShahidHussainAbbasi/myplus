/**
 * Registration page — form, validation, and navigation tests
 *
 * The registration page is served as a static HTML file at /registration.html
 * and is publicly accessible (no login required).
 */

describe('Registration Page — Loading', () => {
  it('loads without redirecting to login', () => {
    cy.clearCookies()
    cy.visit('/registration.html', { failOnStatusCode: false })
    cy.url().should('not.include', '/login')
  })

  it('page body is visible', () => {
    cy.visit('/registration.html', { failOnStatusCode: false })
    cy.get('body').should('be.visible')
  })

  it('contains a form element', () => {
    cy.visit('/registration.html', { failOnStatusCode: false })
    cy.get('form').should('exist')
  })
})

describe('Registration Page — Form Fields', () => {
  beforeEach(() => {
    cy.visit('/registration.html', { failOnStatusCode: false })
  })

  it('has an email or username field', () => {
    cy.get('input[name="email"], input[type="email"], input[name="username"]').should('exist')
  })

  it('has a password field', () => {
    cy.get('input[name="password"], input[type="password"]').should('exist')
  })

  it('has a submit button', () => {
    cy.get('button[type="submit"], input[type="submit"]').should('exist')
  })

  it('has a link back to login', () => {
    cy.get('a[href*="login"]').should('exist')
  })
})

describe('Registration Page — Navigation', () => {
  it('clicking login link navigates to login page', () => {
    cy.visit('/registration.html', { failOnStatusCode: false })
    cy.get('a[href*="login"]').first().click({ force: true })
    cy.url().should('include', 'login')
  })
})
