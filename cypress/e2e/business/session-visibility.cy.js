/**
 * SESS-1 — a till sees its own signed-in devices, and a dead session says so instead of looping.
 *
 * Design: microservices/docs/slices/sess-1-till-stays-signed-in.md. Written BEFORE the implementation.
 *
 * THE PRODUCTION INCIDENT THIS EXISTS FOR (2026-09-16): a shopkeeper's sale was refused with
 * "Invalid refresh token" → 401. That message is thrown ONLY when the row is ABSENT — the session cap
 * (jwt.max-sessions-per-user=5) had evicted their oldest login to make room for a sixth. Nothing on screen
 * said so, nothing cleared the dead session, and every later click failed the same way.
 *
 * The cap stays at 5 (the user's ruling): the fix is VISIBILITY over sessions, which is the policy
 * SecSecurityConfig already states, plus a way to free a slot.
 *
 * ⚠ A SECOND SESSION IS MADE THROUGH THE GATEWAY, never a second browser login. cy.request to
 * /api/auth/login mints a real refresh-token row without touching this spec's own browser session — a second
 * UI login would evict the very session under test and the failure would look like the product.
 *
 * Run headed:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/session-visibility.cy.js
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'

/*
 * ⚠ NEVER owner.business@ — AND THIS IS NOT SPEC HYGIENE, IT IS THE SLICE'S OWN SUBJECT.
 *
 * Sessions are capped per ACCOUNT (jwt.max-sessions-per-user, 5) and a new login evicts the OLDEST row. So a
 * spec that signs in — or worse, fills the cap — as an account a HUMAN is working in takes their till down:
 * exactly the production shape this slice exists for, reproduced by the gate that is meant to prove it fixed.
 * The user works in owner.business@; myplus-f9's auth-per-device-logout gate uses cashier.b@ and
 * user.business@. cashier.a@ is therefore free, disposable, and nobody's working account.
 */
const WHO = 'cashier.a@myplus.com'

/** A real extra session for the SAME account, straight from auth-service. Returns its refresh token. */
function extraSession(label) {
  return cy.request({
    method: 'POST', url: `${GW}/api/auth/login`,
    headers: { 'Content-Type': 'application/json' },
    body: { email: WHO, password: PW }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `${label}: login (${JSON.stringify(r.body).slice(0, 160)})`).to.eq(200)
    const rt = r.body && r.body.data && r.body.data.refreshToken
    expect(rt, `${label}: a refresh token came back`).to.be.a('string')
    return rt
  })
}

/** What the header chip currently reports, read through the app's own endpoint. */
const sessions = () =>
  cy.request({ url: '/mySessions', failOnStatusCode: false }).then((r) => {
    expect(r.status, `GET /mySessions (${JSON.stringify(r.body).slice(0, 160)})`).to.eq(200)
    return r.body
  })

describe('SESS-1 — the shopkeeper can see and clear their own devices', () => {
  beforeEach(() => { cy.loginAsCashierA() })

  it('⭐⭐ 1 — the chip counts THIS account\'s devices, and a new sign-in moves it', () => {
    cy.visit('/businessDashboard')
    cy.waitForAppReady()

    /*
     * Start from a KNOWN number instead of whatever earlier specs left behind — using the feature itself, so
     * a broken revoke shows up here as well. Without this the count is whatever the day has accumulated, and
     * at the cap a new login evicts instead of incrementing, which would read as the chip being wrong.
     */
    cy.request({ method: 'POST', url: '/revokeOtherSessions', failOnStatusCode: false })
    sessions().should((s) => expect(s.count, 'cleared down to this browser session').to.eq(1))

    /*
     * RELOAD, because that revoke went STRAIGHT TO THE SERVER.
     *
     * sessions.js reads /mySessions once at load and then polls every POLL_MS (120000) — slow on purpose,
     * since a device count changes when somebody signs in, not second by second, and this runs on every
     * dashboard of every signed-in user. So a revoke the PAGE did not make cannot reach the chip inside any
     * sane assertion timeout: this case failed on exactly that, asserting 1 for 20s while the chip honestly
     * displayed the 2 it had read at load.
     *
     * This is the same move the second half of this case already makes after the extra login, and the
     * contract it relies on: a reload re-reads at once. What is NOT being tested here is live propagation —
     * the chip updating without a reload is proven in case 3, where the UI's own button paints the fresh
     * count from the revoke's answer.
     */
    cy.reload()
    cy.waitForAppReady()

    // The chip is in the header beside the account pill, on every screen.
    cy.get('[data-sessions]').should('be.visible')
    cy.get('[data-sessions-count]', { timeout: 20000 })
      .should(($el) => expect(Number($el.text().trim()), 'one device, counted').to.eq(1))

    extraSession('a second device')
    cy.reload()                                    // the chip polls slowly on purpose; a reload re-reads at once
    cy.waitForAppReady()
    cy.get('[data-sessions-count]', { timeout: 20000 })
      .should(($el) => expect(Number($el.text().trim()), 'the second device is counted').to.eq(2))
  })

  it('⭐⭐ 2 — at the cap it warns that a new sign-in ends the oldest', () => {
    /*
     * STUBBED, DELIBERATELY. Filling five real slots would evict the OLDEST row — which, on a clean run, is
     * this spec's own browser session, so the test would break itself and read as a product fault. Worse, on
     * a shared account it is the very harm the slice exists to prevent.
     *
     * What the cap DOES is proven where it can be proven honestly: RefreshTokenServiceTest ("at the cap the
     * OLDEST session is evicted, and only it", "atCap is true at the cap"). What can only be proven HERE is
     * that the screen says so — so the count is stubbed and the WORDING is the assertion.
     */
    cy.intercept('GET', '**/mySessions', {
      statusCode: 200,
      body: { count: 5, max: 5, atCap: true, oldestSignedInAt: new Date(Date.now() - 864e5).toISOString() },
    }).as('atCap')

    cy.visit('/businessDashboard')
    cy.wait('@atCap')
    cy.get('[data-sessions]', { timeout: 20000 }).should('have.attr', 'data-at-cap', 'true')
    cy.get('[data-sessions]').invoke('attr', 'title').should((t) => {
      // The sentence that would have saved the shopkeeper's sale: what happens NEXT, not just a number.
      expect(String(t).toLowerCase(), `the chip says what happens next: "${t}"`).to.match(/oldest/)
    })
    cy.get('[data-sessions-count]').should('have.text', '5')
    cy.get('[data-sessions-max]').should('have.text', '5')
  })

  it('⭐⭐ 3 — "Sign out other devices" leaves THIS session working', () => {
    extraSession('a device to be signed out')
    cy.visit('/businessDashboard')
    cy.waitForAppReady()

    cy.get('[data-sessions-signout]').click()
    cy.get('[data-ui-confirm="ok"]').click()          // the app dialog, never window.confirm

    // Exactly one session left — and it is OURS, proven by still being able to read through the proxy.
    cy.get('[data-sessions-count]', { timeout: 20000 })
      .should(($el) => expect(Number($el.text().trim()), 'only this device remains').to.eq(1))
    cy.request({ url: '/getUserProduct?includeInactive=false', failOnStatusCode: false })
      .its('status').should('eq', 200)
  })

  it('⭐ 4 — one account can never see or end another\'s sessions', () => {
    /*
     * The endpoints take NO id — identity comes from the token — so this asserts the property rather than a
     * guard clause: a different tenant's call answers about ITSELF, never about us.
     */
    let mine
    sessions().then((s) => { mine = s })
    cy.asOtherTenant((auth) => {
      cy.request({ url: `${GW}/api/auth/sessions`, headers: auth, failOnStatusCode: false }).then((r) => {
        expect(r.status, 'the other tenant is answered, not refused').to.eq(200)
        const theirs = r.body && r.body.data ? r.body.data : r.body
        expect(theirs.count, 'and told about THEIR sessions').to.be.a('number')
        cy.then(() => {
          // Nothing of ours moved: the only way to know is to re-read our own.
          sessions().then((after) => {
            expect(after.count, 'our count is untouched by their call').to.eq(mine.count)
          })
        })
      })
    })
  })

  it('⭐⭐ 5 — a dead session says so in the shopkeeper\'s words and goes to the login page', () => {
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')

    // The exact answer the monolith gives once its refresh has failed (SessionExpiredAdvice).
    cy.intercept('POST', '**/addSell', {
      statusCode: 401,
      body: { success: false, code: 'SESSION_EXPIRED', message: 'Your session has ended. Sign in again.' },
    }).as('dead')

    cy.window().then((w) => { w.data = [{ productId: 1, itemName: 'probe', quantity: 1, sellRate: 10, totalAmount: 10 }] })
    cy.get('#sellRec').clear().type('10')
    cy.get('#addSell').click()
    /*
     * ANSWER THE DIALOG, or no request is ever made.
     *
     * pos.sale.confirmOnComplete DEFAULTS TO TRUE and the client fails OPEN (absent => on), so
     * jsonPost("addSell", ...) runs only once "Complete this sale?" is answered. Without this line the
     * cy.wait() below dies with "No request ever occurred" — which reads like a broken sale rather than an
     * unanswered question, and is the trap commands.js documents above confirmSale().
     *
     * Deliberately NOT { optional: true }: this tenant has never turned the setting off, so the dialog is
     * expected, and a precondition that skips itself silently is how a gate passes while proving nothing.
     */
    cy.confirmSale()
    cy.wait('@dead')

    // Told, in words a shopkeeper can act on — never "Invalid or expired token".
    cy.contains(/session has ended|sign in again/i, { timeout: 10000 }).should('be.visible')
    // …and taken somewhere that works, rather than left to click into the same failure for ever.
    cy.location('pathname', { timeout: 15000 }).should('match', /\/login/)
  })

  it('⭐ 6 — the failed sale wrote nothing, and cannot come back to be charged twice', () => {
    /*
     * ⚠ REWRITTEN 2026-09-16, because what this case used to assert CANNOT BE TRUE alongside case 5.
     *
     * It asserted that the cart and the idempotency key survive. They do not, and they cannot: case 5
     * requires the till to LAND ON /login, `data` and `saleIdempotencyKey` live in `window`, and a
     * navigation takes both with it. The design note this came from ("not touched — main.js:1295-1298
     * already keeps both") is true of a 200 answer carrying status != SUCCESS, and false of the 401 path
     * this slice introduces. One of the two cases had to go, and it is not the one that gets the shopkeeper
     * back to a working screen.
     *
     * KNOWN LIMIT, stated rather than tested away: a part-rung basket IS lost when a session dies mid-sale.
     * park.js cannot rescue it — parkCurrentSale POSTs /parkSale, which would 401 in exactly this state — so
     * keeping a basket across expiry means browser storage, and that is a decision about where a shop's
     * unsaved basket lives, not a patch to this gate. The user's ruling (2026-09-16): state the limit here,
     * decide that separately.
     *
     * WHAT IS PROVEN INSTEAD is the property that actually protects the shop's money — the sale was refused
     * before it was written, exactly ONE attempt left the browser, and nothing survives that could be
     * completed a second time by a cashier who signs back in and finds a basket waiting.
     */
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')

    cy.intercept('POST', '**/addSell', {
      statusCode: 401,
      body: { success: false, code: 'SESSION_EXPIRED', message: 'Your session has ended. Sign in again.' },
    }).as('dead')

    cy.window().then((w) => {
      w.data = [{ productId: 1, itemName: 'probe', quantity: 1, sellRate: 10, totalAmount: 10 }]
      w.saleIdempotencyKey = 'sess1-probe-key'
    })
    cy.get('#sellRec').clear().type('10')
    cy.get('#addSell').click()
    /*
     * ANSWER THE DIALOG, or no request is ever made.
     *
     * pos.sale.confirmOnComplete DEFAULTS TO TRUE and the client fails OPEN (absent => on), so
     * jsonPost("addSell", ...) runs only once "Complete this sale?" is answered. Without this line the
     * cy.wait() below dies with "No request ever occurred" — which reads like a broken sale rather than an
     * unanswered question, and is the trap commands.js documents above confirmSale().
     *
     * Deliberately NOT { optional: true }: this tenant has never turned the setting off, so the dialog is
     * expected, and a precondition that skips itself silently is how a gate passes while proving nothing.
     */
    cy.confirmSale()
    cy.wait('@dead')

    // ONE attempt, not two. A silent client retry is where a double-charge would come from, since the
    // server dedups on (org, key) and only a repeat that still carries the key is safe.
    cy.get('@dead.all').should('have.length', 1)

    // The till is taken somewhere it can work again — the same landing case 5 asserts, checked here because
    // it is what makes the rest of this case meaningful.
    cy.location('pathname', { timeout: 15000 }).should('match', /\/login/)

    /*
     * And nothing comes BACK. Signing in again must not resurrect the basket: a cashier who finds a
     * part-rung sale waiting will complete it, and if the original had reached the server that is the second
     * invoice this whole guard exists to prevent. An empty cart is the honest, safe state.
     */
    cy.loginAsCashierA()
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')
    cy.window().should((w) => {
      expect((w.data || []).length, 'no basket is resurrected for a second charge').to.eq(0)
      expect(w.saleIdempotencyKey || null, 'and no stale key is carried into the new session').to.not.eq('sess1-probe-key')
    })
  })

  it('7 — an ordinary refusal still shows ITS message and does NOT redirect', () => {
    /*
     * The regression a blanket redirect would cause: "Insufficient stock" is an ANSWER, and it must reach the
     * cashier on the sale screen, not bounce them to a login page.
     */
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')

    cy.intercept('POST', '**/addSell', {
      statusCode: 200,
      body: { status: 'ERROR', message: 'Insufficient stock for Probe Item' },
    }).as('refused')

    cy.window().then((w) => { w.data = [{ productId: 1, itemName: 'probe', quantity: 1, sellRate: 10, totalAmount: 10 }] })
    cy.get('#sellRec').clear().type('10')
    cy.get('#addSell').click()
    /*
     * ANSWER THE DIALOG, or no request is ever made.
     *
     * pos.sale.confirmOnComplete DEFAULTS TO TRUE and the client fails OPEN (absent => on), so
     * jsonPost("addSell", ...) runs only once "Complete this sale?" is answered. Without this line the
     * cy.wait() below dies with "No request ever occurred" — which reads like a broken sale rather than an
     * unanswered question, and is the trap commands.js documents above confirmSale().
     *
     * Deliberately NOT { optional: true }: this tenant has never turned the setting off, so the dialog is
     * expected, and a precondition that skips itself silently is how a gate passes while proving nothing.
     */
    cy.confirmSale()
    cy.wait('@refused')

    cy.contains(/insufficient stock/i, { timeout: 10000 }).should('be.visible')
    cy.location('pathname').should('include', '/businessDashboard')
  })
})
