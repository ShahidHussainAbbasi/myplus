/**
 * Slice-20 — captcha ON: the reCAPTCHA widget renders on the auth forms.
 * REQUIRES the monolith running with CAPTCHA_ENABLED=true (app.captcha.enabled). Run after restarting
 * the monolith with that flag; it is skipped in the default (captcha-off) build.
 */
describe('Slice-20 — captcha ON (needs CAPTCHA_ENABLED=true)', () => {
  it('shows the reCAPTCHA widget + script on login / registration / forgot-password', () => {
    cy.visit('/login')
    cy.get('.g-recaptcha').should('exist').and('have.attr', 'data-sitekey')
    cy.get('script[src*="recaptcha/api.js"]').should('exist')

    cy.visit('/registration.html')
    cy.get('.g-recaptcha').should('exist')

    cy.visit('/forgetPassword.html')
    cy.get('.g-recaptcha').should('exist')
  })
})
