/**
 * 107 Part 1 — the session policy: many devices, and the session id rotates at sign-in.
 * Design: microservices/docs/slices/107-session-policy-and-active-sessions.md ("Part 1 BUILT — awaiting build + gate").
 *
 * Part 1 changed two lines of SecSecurityConfig: maximumSessions(1) → -1 (a till, a back-office PC and a phone may all
 * be signed in — the user's ruling: "follow what banks and Google do") and sessionFixation none() → changeSessionId()
 * (an id planted before sign-in must not survive it). Neither had a gate. Written by the verify sweep, 2026-10-05.
 *
 * Driven with cy.request against /login rather than two browsers: cy.request keeps its OWN cookie jar per call when
 * the cookie is passed explicitly, which is exactly "a second device".
 */
const USER = 'owner.business@myplus.com'
const PW = 'Demo@2025!'

/** Sign in on a "device" that starts with no cookies; yields that device's JSESSIONID. */
const signInFresh = (plantedId) => {
  cy.clearCookies()
  if (plantedId) cy.setCookie('JSESSIONID', plantedId)
  return cy.request('/login').then((page) => {
    const csrf = (/name="_csrf"[^>]*value="([^"]+)"/.exec(page.body) || [])[1]
    expect(csrf, 'the login page carries a CSRF token').to.be.a('string')
    return cy.getCookie('JSESSIONID').then((before) => cy.request({
      method: 'POST', url: '/login', form: true, followRedirect: false,
      body: { username: USER, password: PW, _csrf: csrf },
    }).then((r) => {
      expect(r.status, 'sign-in redirects').to.eq(302)
      expect(r.headers.location, 'into the app, not back to /login?error').to.not.match(/login/)
      return cy.getCookie('JSESSIONID').then((after) => ({ before: before && before.value, after: after.value }))
    }))
  })
}
const alive = (sessionId) => {
  cy.clearCookies()
  cy.setCookie('JSESSIONID', sessionId)
  return cy.request({ url: '/getBusinessDashboardStats', followRedirect: false, failOnStatusCode: false })
}

describe('107 Part 1 — session policy', () => {
  it('⭐ the same account signed in on TWO devices: both stay signed in', () => {
    signInFresh().then((first) => {
      signInFresh().then((second) => {
        expect(second.after, 'two devices, two sessions').to.not.eq(first.after)
        alive(first.after).its('status').should('eq', 200)      // the first device was NOT thrown out
        alive(second.after).its('status').should('eq', 200)
      })
    })
  })

  it('⭐ the session id CHANGES at sign-in (fixation protection)', () => {
    /*
     * A session must exist BEFORE sign-in for this to mean anything. The login page itself opens none (CSRF rides a
     * cookie), but a FAILED sign-in does — Spring keeps the error in it. That id is what an attacker could plant;
     * signing in on the same browser must replace it, and the old id must be worthless afterwards.
     */
    cy.clearCookies()
    cy.request('/login').then((page) => {
      const csrf = (/name="_csrf"[^>]*value="([^"]+)"/.exec(page.body) || [])[1]
      cy.request({ method: 'POST', url: '/login', form: true, followRedirect: false,
        body: { username: USER, password: 'wrong-' + Date.now(), _csrf: csrf } })
    })
    cy.getCookie('JSESSIONID').then((pre) => {
      expect(pre, 'a failed sign-in opened a session').to.not.eq(null)
      cy.request('/login').then((page) => {
        const csrf = (/name="_csrf"[^>]*value="([^"]+)"/.exec(page.body) || [])[1]
        cy.request({ method: 'POST', url: '/login', form: true, followRedirect: false,
          body: { username: USER, password: PW, _csrf: csrf } }).its('headers.location').should('not.match', /login/)
      })
      cy.getCookie('JSESSIONID').then((post) => {
        expect(post.value, 'a different id after sign-in').to.not.eq(pre.value)
        alive(pre.value).its('status').should('not.eq', 200)      // the pre-login id is worthless
        alive(post.value).its('status').should('eq', 200)
      })
    })
  })
})
