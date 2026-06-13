/**
 * Slice-20 — default config (captcha OFF, 2FA ON): no captcha widget on the auth forms, login still
 * works, and the 2FA code field is present. Runs against the default app config.
 */
describe('Slice-20 — captcha OFF (default) + 2FA ON', () => {
  it('login page has no captcha widget, shows the 2FA field, and login works', () => {
    cy.visit('/login')
    cy.get('.g-recaptcha').should('not.exist')
    cy.get('input[name="code"]').should('exist') // 2FA on by default
    cy.get('input[name="username"]').type('demo.appointment@myplus.com')
    cy.get('input[name="password"]').type('Demo@2025!')
    cy.get('#loginSubmit').click()
    cy.url().should('include', '/appointmentDashboard')
  })

  it('registration and forgot-password show no captcha widget', () => {
    cy.visit('/registration.html')
    cy.get('.g-recaptcha').should('not.exist')
    cy.visit('/forgetPassword.html')
    cy.get('.g-recaptcha').should('not.exist')
  })
})
