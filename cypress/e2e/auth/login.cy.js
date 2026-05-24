/**
 * Authentication flow tests — login, logout, session handling
 *
 * Login page uses <button id="loginSubmit" type="submit"> (redesigned from input[type="submit"]).
 * Validation is handled by validateLogin() in login.js — uses window.alert().
 * Error messages on bad credentials use class .msg-bar.error (not Bootstrap .alert-danger).
 */

describe('Login Page', () => {
  beforeEach(() => {
    cy.visit('/login')
  })

  it('should load the login page with correct elements', () => {
    cy.get('input[name="username"]').should('be.visible')
    cy.get('input[name="password"]').should('be.visible')
    cy.get('form[name="f"]').should('exist')
    cy.get('#loginSubmit').should('be.visible')
  })

  it('should redirect unauthenticated user from protected route to login', () => {
    cy.clearCookies()
    cy.visit('/businessDashboard', { failOnStatusCode: false })
    cy.url().should('include', '/login')
  })

  it('should show alert and not redirect with empty credentials', () => {
    cy.on('window:alert', (text) => {
      // validateLogin() message: "Please enter your username and password"
      expect(text).to.include('enter')
    })
    cy.get('#loginSubmit').click()
    cy.url().should('include', '/login')
  })

  it('should show alert when only username is empty', () => {
    cy.on('window:alert', (text) => {
      // validateLogin() message: "Please enter your username"
      expect(text).to.include('username')
    })
    cy.get('input[name="password"]').type('anypassword')
    cy.get('#loginSubmit').click()
    cy.url().should('include', '/login')
  })

  it('should show alert when only password is empty', () => {
    cy.on('window:alert', (text) => {
      // validateLogin() message: "Please enter your password"
      expect(text).to.include('password')
    })
    cy.get('input[name="username"]').type('test@test.com')
    cy.get('#loginSubmit').click()
    cy.url().should('include', '/login')
  })

  it('should show error message with invalid credentials', () => {
    cy.get('input[name="username"]').type('invalid@user.com')
    cy.get('input[name="password"]').type('WrongPassword123!')
    cy.get('#loginSubmit').click()
    // Spring Security redirects to /login?error after failure
    cy.url().should('include', '/login')
    // Login redesign uses .msg-bar.error (not Bootstrap .alert-danger)
    cy.get('.msg-bar.error').should('be.visible')
  })

  it('should have registration link on login page', () => {
    cy.get('a[href*="registration"]').should('exist').and('be.visible')
  })

  it('should navigate to registration page from login link', () => {
    cy.get('a[href*="registration"]').first().click()
    cy.url().should('include', 'registration')
  })
})

describe('Logout', () => {
  it('should log out and leave the dashboard', () => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
    cy.url().should('include', '/businessDashboard')
    cy.visit('/logout')
    // App may redirect to /logout.html, /home, or /login depending on the LogoutSuccessHandler
    cy.url().should('not.include', '/businessDashboard')
  })
})
