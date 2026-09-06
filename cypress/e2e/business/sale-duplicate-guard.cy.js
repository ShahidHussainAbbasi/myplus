/**
 * SF-3b — one press, one sale, even when the work AFTER the sale fails.
 *
 * WHAT THIS ASSERTS, AND WHY IT IS NOT ALREADY COVERED.
 *
 * The server dedups correctly: `uq_ch_org_idempotency` is UNIQUE on (organization_id, idempotency_key), so
 * two submissions carrying the SAME key can only ever produce one invoice. Every existing sale spec proves
 * that half.
 *
 * This file is about the other half — the client losing the key while the cart survives.
 *
 * jsonPost's success handler used to retire the idempotency key first and clear the cart LAST, with
 * printReceipt(), dispensePrescription() and loadDataTable() in between. A throw in any of those three left
 * a RETIRED key against a FULL cart: the till still shows the basket, so the cashier believes the sale did
 * not go through and presses Complete Sale again — and because the old key is gone,
 * getSaleIdempotencyKey() mints a NEW one. The server dedups on (org, key), sees a key it has never seen,
 * and writes a genuine SECOND invoice. The shop is owed twice for goods it handed over once.
 *
 * Nothing about that is visible to a test that only checks the happy path, because on the happy path
 * nothing throws. So this spec MAKES a post-sale side effect throw, which is the only honest way to prove
 * the ordering.
 *
 * ⚠ THE NEGATIVE CONTROL MATTERS HERE. Case 1 would also pass against a build where the sale silently
 * failed, so case 0 pins that this same flow really does complete a sale first.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/sale-duplicate-guard.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}`.slice(-8) + Math.floor(Math.random() * 900 + 100)

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success, `saveBusinessConfig ${key}=${value}`).to.eq(true))

/**
 * The overlay covers the till while any $.get is in flight — acting under it is a race, not a test.
 *
 * Tolerant of the element being absent: ajax-overlay.js injects #appAjaxOverlay lazily, so on a screen that
 * has not yet made a request there is nothing to wait for. `should('not.be.visible')` on a missing element
 * FAILS in Cypress rather than passing, which would make this helper red for the one reason that is fine.
 */
const overlayGone = () => {
  cy.get('body').then(($b) => {
    if ($b.find('#appAjaxOverlay').length) {
      cy.get('#appAjaxOverlay', { timeout: 30000 }).should('not.be.visible')
    }
  })
}

/** Ring up one unit of a product, paid in full in cash — the shortest sale that needs no customer. */
const ringUp = (productId, price) => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.get('#sellType').select('sellDiv', { force: true })

  cy.get('#sellItemDD', { timeout: 15000 }).select(String(productId), { force: true })
  cy.get('#sellItems').clear().type('1')
  cy.get('#addInviceItem').click({ force: true })   // sic: the app's id carries the typo
  overlayGone()

  // Cash, paid exactly: a fully-paid sale needs nobody named, which keeps this file about the cart and
  // not about credit rules.
  cy.get('#sellPayMethod').select('CASH', { force: true })
  cy.get('#sellRec').clear().type(String(price))
}

/**
 * Answer the till's confirm dialog. pos.sale.confirmOnComplete defaults ON and the client fails OPEN
 * (absent => on), so this appears for any tenant that has never touched the setting. Keyed on the stable
 * [data-ui-confirm="ok"] hook confirm-dialog.js exposes for tests, not on button text.
 */
const confirmSale = () =>
  cy.get('[data-ui-confirm="ok"]', { timeout: 10000 }).should('be.visible').click({ force: true })

describe('SF-3b — one press, one sale', () => {
  before(() => {
    cy.loginAsOwner()
    setConfig('pos.sale.confirmOnComplete', 'true')
    // The receipt is what case 1 sabotages, so it has to be ON for the sabotage to run at all.
    setConfig('pos.receipt.autoPrint', 'true')
  })

  beforeEach(() => cy.loginAsOwner())

  after(() => {
    // Leave no server state behind - both of these change the till for every later spec.
    cy.loginAsOwner()
    setConfig('pos.sale.confirmOnComplete', 'true')
    setConfig('pos.receipt.autoPrint', 'true')
  })

  // ── 0. the negative control ───────────────────────────────────────────────────────────────────────

  it('0 — an ordinary sale completes and empties the till (the control for case 1)', () => {
    const run = uniq()
    cy.seedProduct({ name: `DUP0_${run}`, sellingPrice: 500, stock: 5 }).then(({ productId }) => {
      ringUp(productId, 500)

      cy.intercept('POST', '**/addSell').as('sale')
      cy.get('#addSell').click({ force: true })
      confirmSale()

      cy.wait('@sale', { timeout: 20000 }).then((i) => {
        expect(i.response.body.status, JSON.stringify(i.response.body)).to.eq('SUCCESS')
      })

      // The two halves of "this checkout is finished".
      cy.window().its('data').should('have.length', 0)
      cy.window().its('saleIdempotencyKey').should('not.be.ok')
    })
  })

  // ── 1. ⭐⭐ the case this file exists for ──────────────────────────────────────────────────────────

  it('⭐⭐ 1 — a receipt that THROWS still leaves the till empty and the key retired', () => {
    const run = uniq()
    cy.seedProduct({ name: `DUP1_${run}`, sellingPrice: 500, stock: 5 }).then(({ productId }) => {
      ringUp(productId, 500)

      /*
       * Sabotage the receipt, the way a real printer problem does.
       *
       * Replacing the global is exactly how the product reaches it: jsonPost calls the bare identifier
       * `printReceipt(data.object)` after a `typeof printReceipt === 'function'` check, so a global that
       * throws is indistinguishable from receipt.js failing on a malformed invoice.
       *
       * Stubbed AFTER the page is loaded and the cart is built - main.js has already been evaluated by
       * then, and the call site resolves the global at CALL time, not at load.
       */
      cy.window().then((win) => {
        win.printReceipt = () => { throw new Error('printer offline (deliberate: SF-3b)') }
      })

      cy.intercept('POST', '**/addSell').as('sale')
      cy.get('#addSell').click({ force: true })
      confirmSale()

      cy.wait('@sale', { timeout: 20000 }).then((i) => {
        // The sale itself must still have committed - the receipt is downstream of the money.
        expect(i.response.body.status, `the sale must commit: ${JSON.stringify(i.response.body)}`)
          .to.eq('SUCCESS')
      })

      /*
       * THE ASSERTION. Before the fix these two were the failure: the cart still held its line and the key
       * was already null, which is the precise state that turns the cashier's next press into a second
       * invoice.
       */
      cy.window().its('data', { timeout: 10000 })
        .should('have.length', 0)          // the cart was cleared despite the throw
      cy.window().its('saleIdempotencyKey')
        .should('not.be.ok')               // and the key was retired with it
    })
  })

  // ── 2. and therefore a second press cannot bill the customer twice ────────────────────────────────

  it('⭐ 2 — pressing Complete Sale again after a failed receipt does NOT write a second invoice', () => {
    const run = uniq()
    cy.seedProduct({ name: `DUP2_${run}`, sellingPrice: 500, stock: 5 }).then(({ productId }) => {
      ringUp(productId, 500)

      cy.window().then((win) => {
        win.printReceipt = () => { throw new Error('printer offline (deliberate: SF-3b)') }
      })

      let posts = 0
      cy.intercept('POST', '**/addSell', () => { posts += 1 }).as('sale')

      cy.get('#addSell').click({ force: true })
      confirmSale()
      cy.wait('@sale', { timeout: 20000 })

      /*
       * The cashier's second press - the whole point. With an empty cart the client refuses it before any
       * request is made ("Please add items to the cart..."), which is the behaviour that protects the
       * customer. Asserted by COUNTING requests rather than by looking for an absence, because "no second
       * request appeared yet" and "no second request will appear" are different claims.
       */
      cy.get('#addSell').click({ force: true })

      // Long enough that a second post would have been made if the cart had survived. Not a race: the
      // click handler is synchronous up to the point it either refuses or fires.
      cy.wait(2000)
      cy.then(() => {
        expect(posts, 'exactly ONE sale was submitted, however many times the button was pressed').to.eq(1)
      })
    })
  })
})
