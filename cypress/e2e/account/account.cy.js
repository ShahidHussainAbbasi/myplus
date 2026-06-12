/**
 * P4 runtime verification — the monolith's account actions delegate to auth-service (no myplusdb).
 *
 *  - change-password (logged in) -> PUT /api/auth/users/me/password
 *  - 2FA setup/verify/disable     -> /api/auth/2fa/{setup,verify,disable}
 *
 * Requires the full stack up (eureka, config, gateway, auth-service, monolith). Uses the seeded
 * education super user. NOTE: auth-service /2fa/setup ENABLES 2FA immediately, so the suite always
 * disables afterward (after hook) to avoid locking the shared user into a 2FA login.
 */
describe('P4 — account actions delegate to auth-service', () => {
  beforeEach(() => {
    cy.loginAs('super@edu.com', 'super', '/getDashboardData')
  })

  it('rejects change-password when the current password is wrong (delegated check)', () => {
    cy.request({
      method: 'POST', url: '/user/updatePassword', form: true,
      body: { oldPassword: 'definitely-wrong-pw', newPassword: 'NewPassw0rd!1', matchingPassword: 'NewPassw0rd!1' },
      failOnStatusCode: false,
    }).then((res) => {
      // monolith maps an auth-service 4xx to InvalidOldPasswordException
      expect(res.status).to.be.greaterThan(399)
      expect(JSON.stringify(res.body)).to.match(/InvalidOldPassword|old password|Invalid/i)
    })
  })

  describe('2FA enrolment (setup -> verify -> disable)', () => {
    // Guaranteed cleanup — setup enables 2FA, so never leave the user enrolled.
    after(() => {
      cy.loginAs('super@edu.com', 'super', '/getDashboardData')
      cy.request({ method: 'POST', url: '/user/2fa/disable', failOnStatusCode: false })
    })

    it('sets up, verifies a real authenticator code, and disables', () => {
      cy.request({ method: 'POST', url: '/user/2fa/setup' }).then((res) => {
        expect(res.status).to.eq(200)
        const otpauth = res.body.message
        expect(otpauth, 'otpauth:// provisioning URI').to.match(/^otpauth:\/\//)
        const secret = /[?&]secret=([^&]+)/.exec(otpauth)[1]

        // a wrong code is rejected
        cy.request({ method: 'POST', url: '/user/2fa/verify', form: true, body: { code: '000000' }, failOnStatusCode: false })
          .then((wrong) => expect(JSON.stringify(wrong.body)).to.match(/Invalid/i))

        // the current TOTP from the secret verifies
        cy.task('totp', { secret }).then((code) => {
          cy.request({ method: 'POST', url: '/user/2fa/verify', form: true, body: { code } }).then((ok) => {
            expect(ok.status).to.eq(200)
            expect(ok.body.error, 'no error on valid code').to.be.oneOf([null, undefined])
            expect(ok.body.message).to.match(/enabled|verified|success/i)
          })
        })

        // disable restores the account
        cy.request({ method: 'POST', url: '/user/2fa/disable' }).then((off) => {
          expect(off.status).to.eq(200)
        })
      })
    })
  })
})
