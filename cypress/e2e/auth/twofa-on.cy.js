/**
 * Slice-20 — 2FA field ON: the login form offers an authenticator code box.
 * REQUIRES the monolith running with TWOFA_ENABLED=true (app.twofa.enabled). It is skipped in the
 * default build, exactly as captcha-on.cy.js is — a server flag cannot be flipped from a test, so the
 * two builds are covered by two specs rather than by one that lies about which one it is running in.
 *
 * The pair to this is captcha-off.cy.js, which asserts the default build's ABSENCE of both.
 */
describe('Slice-20 — 2FA field ON (needs TWOFA_ENABLED=true)', () => {
  it('renders the authenticator code field, and login still works without a code for an unenrolled user', function () {
    cy.visit('/login')
    // Self-skip in the default build: no field means app.twofa.enabled=false, so this is not applicable.
    cy.get('body').then(($b) => {
      if ($b.find('input[name="code"]').length === 0) {
        cy.log('No authenticator field — 2FA is disabled; skipping (run with TWOFA_ENABLED=true).')
        this.skip()
      }
    })

    // A one-time-code input, not just any text box: the autocomplete token is what lets a phone or
    // password manager fill the code, and getting it wrong makes 2FA materially worse to use.
    cy.get('input[name="code"]')
      .should('exist')
      .and('have.attr', 'autocomplete', 'one-time-code')
      .and('have.attr', 'inputmode', 'numeric')

    // The field is OPTIONAL — its own label says "only if 2FA is enabled". A user who has not enrolled
    // must still be able to sign in with the box left empty; requiring it would lock out every user
    // the moment the flag was turned on.
    cy.get('input[name="username"]').type('demo.appointment@myplus.com')
    cy.get('input[name="password"]').type('Demo@2025!')
    cy.get('#loginSubmit').click()
    cy.url().should('include', '/appointmentDashboard')
  })
})
