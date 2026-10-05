/**
 * SESS-2 — an expired session takes the person to the login page, never to a "parsererror".
 *
 * ── The defect (Test Book §13, open since the session work) ─────────────────────────────────────────────────
 * "Session expiry shows a parser error instead of the login page." Traced on the running stack, 2026-10-05:
 *
 *   1. The monolith's session expires (or the server restarts). The browser still holds the old JSESSIONID.
 *   2. The next click fires an XHR. `invalidSessionUrl("/invalidSession.html")` answers it with a 302.
 *   3. jQuery FOLLOWS the redirect and receives the "Session expired" HTML page, status 200, where it asked for JSON.
 *   4. `dataType:'json'` fails to parse it → textStatus "parsererror".
 *   5. `handleAjaxFailure` looks for the three session-lost shapes it knows (a 401, the LOGIN page, the concurrent-
 *      session notice). The invalid-session page is none of them, so it shows "form submit: parsererror (200)".
 *
 * Every piece was individually right: SESS-1 already turns a 401 {code:"SESSION_EXPIRED"} into a redirect with the
 * sentence in the person's language — but the INVALID-session path never produced that answer for an XHR.
 *
 * ── What is asserted ────────────────────────────────────────────────────────────────────────────────────────
 * Case 1 drives the real screen: a signed-in owner whose session dies, then clicks. A dead session is simulated by
 * replacing the JSESSIONID with one the server never issued — the server takes the identical branch (requested
 * session id not valid → InvalidSessionStrategy), proven by case 3 answering exactly as an expired one does.
 *
 * Run: npx cypress run --spec cypress/e2e/security/session-expiry-xhr.cy.js
 */
const OWNER = 'owner.business@myplus.com'
const DEAD = 'DEADBEEF0000000000000000SESS2XHR'

describe('SESS-2 — an expired session goes to the login page, not a parser error', () => {
  it('⭐ a click after the session died lands on the login page, saying the session ended', () => {
    cy.loginAsOwner(OWNER)
    cy.visitDashboardSettled()

    // The session dies while the screen is open.
    cy.setCookie('JSESSIONID', DEAD)

    // The person's next click reads from the server through an ordinary request with no failure handler of its own
    // — most of the app's calls look like this. (Braced so Cypress does not await the rejected jqXHR.)
    cy.window().then((w) => { w.$.ajax({ url: w.serverContext + 'getUserSell', dataType: 'json' }) })

    cy.location('pathname', { timeout: 15000 }).should('eq', '/login')
    cy.location('search').then((q) => {
      const note = decodeURIComponent(q.replace(/\+/g, ' '))
      expect(note, 'the login page is told WHY').to.match(/message=/)
      expect(note, 'never jQuery\'s word for a parse failure').to.not.match(/parsererror/i)
    })
    cy.get('input[name="username"]').should('be.visible')
  })

  it('⭐⭐ a BACKGROUND read meets the dead session first — the person still lands on the login page', () => {
    /*
     * Found by the guide walk (V7) after the first SESS-2 fix had gone green. On a real dashboard the first request to
     * meet a dead session is not the person's click — it is a background read (tiles, pickers, the returns register:
     * `global:false`, so no global ajax event ever sees it). Its 401 was swallowed, the answer handed the browser a
     * FRESH anonymous session, and the click that followed got the LOGIN PAGE where it asked for JSON — parsererror
     * again, with no session-expired code anywhere to recognise.
     */
    cy.loginAsOwner(OWNER)
    cy.visitDashboardSettled()
    cy.setCookie('JSESSIONID', DEAD)
    cy.window().then((w) => { w.bgJson(w.serverContext + 'getSaleReturns') })
    cy.location('pathname', { timeout: 15000 }).should('eq', '/login')
  })

  it('…and a click AFTER the background read used up the dead session also lands on the login page', () => {
    cy.loginAsOwner(OWNER)
    cy.visitDashboardSettled()
    cy.clearCookie('JSESSIONID')           // signed out: what the browser holds once a dead session was replaced
    cy.window().then((w) => { w.$.ajax({ url: w.serverContext + 'getUserSell', dataType: 'json' }) })
    cy.location('pathname', { timeout: 15000 }).should('eq', '/login')
  })

  it('a FORM SUBMIT after the session died also lands on the login page (callAjax path)', () => {
    /*
     * The parsererror branch of main.js's form submit is where the reported text came from ("form submit:
     * parsererror"). It hands off to handleAjaxFailure, which must recognise the answer as a lost session.
     */
    cy.loginAsOwner(OWNER)
    cy.visitDashboardSettled()
    cy.setCookie('JSESSIONID', DEAD)
    cy.window().then((w) => {
      w.$.ajax({ url: w.serverContext + 'getUserSell', dataType: 'json', global: false })
        .fail((x, textStatus, err) => w.handleAjaxFailure(x, err, 'form submit'))
    })
    cy.location('pathname', { timeout: 15000 }).should('eq', '/login')
  })

  it('the server answers an XHR on a dead session with 401 SESSION_EXPIRED — not a redirect to HTML', () => {
    cy.clearCookies()
    cy.setCookie('JSESSIONID', DEAD)
    cy.request({
      url: '/getUserSell', followRedirect: false, failOnStatusCode: false,
      headers: { 'X-Requested-With': 'XMLHttpRequest', Accept: 'application/json, text/javascript, */*; q=0.01' },
    }).then((r) => {
      expect(r.status, 'a status the browser can act on').to.eq(401)
      expect(r.body.code).to.eq('SESSION_EXPIRED')
      expect(r.body.success).to.eq(false)
    })
  })

  it('REGRESSION: a page load on a dead session still shows the Session expired page', () => {
    // The page a PERSON navigates to is unchanged — only script requests get the JSON answer.
    cy.clearCookies()
    cy.setCookie('JSESSIONID', DEAD)
    cy.request({ url: '/businessDashboard', followRedirect: false, failOnStatusCode: false }).then((r) => {
      expect(r.status).to.eq(302)
      expect(r.headers.location).to.match(/\/invalidSession\.html$/)
    })
    // The answer above handed the browser a FRESH session id (that is what stops /login looping) — so plant the dead
    // one again, or the visit arrives merely signed-out and lands on Sign In instead.
    cy.setCookie('JSESSIONID', DEAD)
    cy.visit('/businessDashboard')
    cy.title().should('match', /session expired/i)
  })
})
