/**
 * Slice-20 — the DEFAULT auth build: captcha OFF, 2FA field OFF. No widget on any auth form, no
 * authenticator box on the login form, and login still works.
 *
 * ── Why this changed ─────────────────────────────────────────────────────────────────────────────
 * This spec used to assert `input[name="code"]` EXISTS, on the comment "2FA on by default". That was
 * true when slice-20 shipped (`app.twofa.enabled=${TWOFA_ENABLED:true}`), but commit f57fdbe6 flipped
 * BOTH defaults off and never came back here — so the case has been red ever since, asserting a
 * default the application no longer has.
 *
 * The template is the contract: login.html gates the field with `th:if="${twoFaEnabled}"` and the
 * widget with `th:if="${captchaEnabled}"`. With both flags false the honest assertion is ABSENCE, and
 * absence is worth asserting — a 6-digit "Authenticator code" box shown to every user of a build with
 * 2FA turned off is a real UX defect, not a harmless extra field.
 *
 * The ON side of each flag keeps its own spec, self-skipping in this build: captcha-on.cy.js and
 * twofa-on.cy.js. A server flag cannot be flipped from a test without a restart, which is why these
 * are split by build rather than seeded per case.
 */
describe('Slice-20 — default auth build (captcha OFF + 2FA field OFF)', () => {
  it('login page has no captcha widget, no 2FA field, and login works', () => {
    cy.visit('/login')
    cy.get('.g-recaptcha').should('not.exist')
    cy.get('input[name="code"]').should('not.exist')   // app.twofa.enabled=false → th:if drops the group

    // The positive control: the page is not merely missing things, it still signs a user in.
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
