/**
 * AUTH-SESS-1 — signing out one device must not sign out the others.
 *
 * Design: microservices/docs/slices/auth-sess-1-per-device-logout.md
 *
 * THE DEFECT THIS PINS (confirmed in production 2026-09-16): `POST /api/auth/logout` resolved the USER from the
 * access token and deleted EVERY refresh token that user had. The other devices kept working on their unexpired
 * access tokens and were refused ~15 minutes later — one access-token lifetime — with 400 "Invalid refresh token",
 * which `AuthService.refreshToken` throws ONLY when the row is absent. For a till that arrives mid-sale: the
 * shopkeeper pressed Save and the sale was refused, minutes after somebody signed out in the back office.
 *
 * Case 1 IS the slice: device A's refresh must still answer 200 after device B logs out. Before the fix it answers
 * 400, so the case can genuinely fail.
 *
 * ⚠ ACCOUNTS. This spec signs in repeatedly and signs out, which is precisely what must never be aimed at an account
 * a person is using — that is how the production incident was first (wrongly) suspected of being our own test runs.
 * It uses cashier.b@ and, for the anti-IDOR case, user.business@ — never owner.business@.
 *
 * Everything is the gateway's auth API: two "devices" are two logins holding two refresh tokens, so no second
 * browser is needed. Each case ends the sessions it opened.
 *
 * Run headed, solo:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/auth/auth-per-device-logout.cy.js
 */

const GW = 'http://localhost:8765/api/auth'
const TILL = 'cashier.b@myplus.com'
const OTHER = 'user.business@myplus.com'
const PW = 'Demo@2025!'

/** One device: a login, holding the pair that identifies it. */
const signIn = (email = TILL) =>
  cy.request({
    method: 'POST', url: `${GW}/login`, headers: { 'Content-Type': 'application/json' },
    body: { email, password: PW }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `login ${email}: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(200)
    const d = r.body && r.body.data
    expect(d && d.accessToken, 'login must return an access token').to.be.a('string')
    expect(d && d.refreshToken, 'login must return a refresh token — the device\'s identity').to.be.a('string')
    return { email, access: d.accessToken, refresh: d.refreshToken }
  })

/** What a device does when its access token ages out. 200 = still signed in; 400 = its session is gone. */
const refresh = (device) =>
  cy.request({
    method: 'POST', url: `${GW}/refresh`, headers: { 'Content-Type': 'application/json' },
    body: { refreshToken: device.refresh }, failOnStatusCode: false,
  })

/**
 * Sign out. `body` false omits the refresh token entirely — the compatibility path a client older than this slice
 * takes, which still revokes everything.
 */
const signOut = (device, { withToken = true } = {}) =>
  cy.request({
    method: 'POST', url: `${GW}/logout`,
    headers: { Authorization: `Bearer ${device.access}`, 'Content-Type': 'application/json' },
    body: withToken ? { refreshToken: device.refresh } : {},
    failOnStatusCode: false,
  }).then((r) => {
    // A logout may never fail: one that throws leaves the person signed in.
    expect(r.status, `logout ${device.email}: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(200)
    return r
  })

describe('AUTH-SESS-1 — one device signing out leaves the others signed in', () => {
  it('⭐⭐ 1 — the till stays signed in when the back office signs out', () => {
    signIn().then((till) => {
      signIn().then((backOffice) => {
        // Both are live before anything happens — otherwise case 1 could pass on a session that never existed.
        refresh(till).its('status').should('eq', 200)

        signOut(backOffice)

        refresh(till).then((r) => {
          expect(r.status,
            'the till must still refresh after another device signed out — this answered 400 before AUTH-SESS-1, '
            + `and the next sale was refused: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(200)
          expect(r.body && r.body.data && r.body.data.accessToken, 'and it gets a working access token').to.be.a('string')
        })

        signOut(till)   // leave no live session behind
      })
    })
  })

  it('⭐ 2 — the device that signed out is signed out', () => {
    signIn().then((device) => {
      signOut(device)
      refresh(device).then((r) => {
        expect(r.status, 'its own session is gone').to.eq(400)
        expect(JSON.stringify(r.body)).to.contain('Invalid refresh token')
      })
    })
  })

  it('⭐ 3 — a client that sends no refresh token still revokes everything (the compatibility path)', () => {
    signIn().then((deviceA) => {
      signIn().then((deviceB) => {
        refresh(deviceA).its('status').should('eq', 200)

        signOut(deviceB, { withToken: false })   // an older client: no body

        refresh(deviceA).then((r) => {
          expect(r.status, 'no token presented means "end them all" — unchanged behaviour').to.eq(400)
        })
      })
    })
  })

  it('⭐⭐ 4 — a logout presenting SOMEBODY ELSE\'S token ends nothing (anti-IDOR)', () => {
    signIn(OTHER).then((stranger) => {
      signIn(TILL).then((till) => {
        // The till logs out while presenting the stranger's refresh token.
        cy.request({
          method: 'POST', url: `${GW}/logout`,
          headers: { Authorization: `Bearer ${till.access}`, 'Content-Type': 'application/json' },
          body: { refreshToken: stranger.refresh }, failOnStatusCode: false,
        }).its('status').should('eq', 200)

        refresh(stranger).then((r) => {
          expect(r.status, 'a foreign token must delete NOTHING — one account cannot sign another out').to.eq(200)
        })
        // ⚠ And it must not have fallen back to revoke-all for the caller either: that fallback IS the defect.
        refresh(till).then((r) => {
          expect(r.status, 'an unmatched token must not take the caller\'s own other sessions down').to.eq(200)
        })

        signOut(till)
        signOut(stranger)
      })
    })
  })

  it('⭐ 5 — an unknown or already-used token is tolerated, not an error', () => {
    signIn().then((device) => {
      cy.request({
        method: 'POST', url: `${GW}/logout`,
        headers: { Authorization: `Bearer ${device.access}`, 'Content-Type': 'application/json' },
        body: { refreshToken: 'not-a-real-token-' + Date.now() }, failOnStatusCode: false,
      }).its('status').should('eq', 200)

      refresh(device).then((r) => {
        expect(r.status, 'nothing was deleted — the session that asked is still live').to.eq(200)
      })

      signOut(device)
    })
  })
})
