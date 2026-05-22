/**
 * Authentication flow tests — login, logout, session handling
 *
 * App note: login.html has <input id="submit" name="submit" type="submit">.
 * The name="submit" attribute shadows the native form.submit() method, so
 * cy.get('form').submit() throws "form.submit is not a function".
 * All tests use cy.get('input[type="submit"]').click() instead.
 */

describe('Login Page', () => {
  beforeEach(() => {
    cy.visit('/login')
  })

  it('should load the login page with correct elements', () => {
    cy.get('input[name="username"]').should('be.visible')
    cy.get('input[name="password"]').should('be.visible')
    cy.get('form[name="f"]').should('exist')
    cy.get('input[type="submit"]').should('be.visible')
  })

  it('should redirect unauthenticated user from protected route to login', () => {
    cy.clearCookies()
    cy.visit('/businessDashboard', { failOnStatusCode: false })
    cy.url().should('include', '/login')
  })

  it('should show alert and not redirect with empty credentials', () => {
    cy.on('window:alert', (text) => {
      expect(text).to.include('required')
    })
    cy.get('input[type="submit"]').click()
    cy.url().should('include', '/login')
  })

  it('should show "User Name required" alert when only username is empty', () => {
    cy.on('window:alert', (text) => {
      // message.username = "User Name required"
      expect(text).to.include('User Name required')
    })
    cy.get('input[name="password"]').type('anypassword')
    cy.get('input[type="submit"]').click()
    cy.url().should('include', '/login')
  })

  it('should show "Password required" alert when only password is empty', () => {
    cy.on('window:alert', (text) => {
      // message.password = "Password required"
      expect(text).to.include('Password required')
    })
    cy.get('input[name="username"]').type('test@test.com')
    cy.get('input[type="submit"]').click()
    cy.url().should('include', '/login')
  })

  it('should show error element with invalid credentials', () => {
    cy.get('input[name="username"]').type('invalid@user.com')
    cy.get('input[name="password"]').type('WrongPassword123!')
    cy.get('input[type="submit"]').click()
    // Spring Security redirects to /login?error after failure
    cy.url().should('include', '/login')
    // Error alert div should appear
    cy.get('.alert-danger').should('be.visible')
  })

  it('should have registration link on login page', () => {
    cy.get('a[href*="registration"]').should('exist').and('be.visible')
  })

  it('should navigate to registration page from login link', () => {
    cy.get('a[href*="registration"]').first().click()
    cy.url().should('include', 'registration')
  })

  it('should show forgot password link', () => {
    cy.get('a[href*="forgetPassword"]').should('exist')
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
