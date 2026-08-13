/**
 * POS P3 — quick-pick tiles: the shop's best sellers, one keystroke each.
 *
 * WHY THE FEATURE EXISTS: goods with no barcode (loose produce, bakery, services) fall back to the
 * full item form on every sale, which is the slow path P1/P2 were built to avoid. A tile is a scan
 * without the barcode — it goes through the SAME scanAddToCart the scanner uses, so pricing, the
 * pharmacy Rx warning and the cart totals all behave identically.
 *
 * Same two-halves rule as P1/P2: OFF is the default every tenant has, so "nothing appeared above the
 * cart" is the assertion that protects them.
 *
 * SCOPING IS THE POINT OF THE SERVER TEST HERE. The tiles are ORG-scoped, not per-user: a shared till
 * must show every cashier the same grid, and a newly hired one must not get an empty screen on their
 * first shift. That is why /topProducts exists rather than reusing the dashboard's per-user
 * topSellingItems.
 *
 * Run headed.
 */

function openSell(opts) {
  var o = opts || {}
  // visitSaleScreen waits for loadPosFeatureFlags() to finish writing window.pos* — otherwise the
  // assignments below are racing it, and a failed config call (which fails CLOSED) silently wins.
  cy.visitSaleScreen()
  cy.window().should((w) => {
    expect(w.renderQuickPick, 'pos-keyboard.js exposes renderQuickPick').to.be.a('function')
    expect(w.scanAddToCart, 'business.js exposes scanAddToCart').to.be.a('function')
  })
  cy.window().then((w) => {
    w.posQuickPickEnabled = o.quickPick === true
    w.posShortcutsEnabled = o.shortcuts === true
    // Pinned explicitly, not inherited. pos.keyboard.enabled ships ON now, and a test that silently
    // adopts whatever the catalog default happens to be reports on a configuration nobody chose —
    // and changes meaning the next time that default moves.
    w.posKeyboardEnabled = o.keyboard === true
    w.posQuickPickCount = o.count || 9
    w.posQuickPickDays = o.days || 30
    w.renderQuickPick()
  })
}

function pressKey(key, alt) {
  cy.document().then((doc) => {
    const ev = new doc.defaultView.KeyboardEvent('keydown', {
      key: key, altKey: alt === true, bubbles: true, cancelable: true
    })
    doc.dispatchEvent(ev)
  })
}

describe('P3 — /topProducts endpoint', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('returns a {status, collection} list shaped for the tiles', () => {
    cy.request('/topProducts?days=3650&limit=9').then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body.status).to.eq('SUCCESS')
      expect(r.body.collection, 'GenericResponse carries lists in `collection`').to.be.an('array')
      // A tenant with no sales history legitimately has no best sellers — an empty array is a valid
      // answer, so only the SHAPE of a populated row is asserted.
      ;(r.body.collection || []).forEach((t) => {
        expect(t).to.have.property('productId')
        expect(t).to.have.property('name')
        expect(t).to.have.property('sellingPrice')
        expect(t).to.have.property('units')
      })
    })
  })

  it('honours the limit, and clamps an absurd one rather than dumping the catalogue', () => {
    cy.request('/topProducts?days=3650&limit=2').then((r) => {
      expect((r.body.collection || []).length).to.be.at.most(2)
    })
    cy.request('/topProducts?days=3650&limit=9999').then((r) => {
      expect((r.body.collection || []).length, 'clamped server-side').to.be.at.most(24)
    })
  })

  it('a product sold today appears in the tiles', () => {
    const stamp = Date.now()
    cy.seedProduct({ name: 'QpSold_' + stamp, sku: 'QPS' + stamp, sellingPrice: 40, stock: 50 })
      .then(({ productId, sku }) => {
        // Ring a real sale so there is history to rank. The scan path is the shortest route to one.
        cy.intercept('POST', '**/addSell').as('sale')
        cy.visit('/businessDashboard')
        cy.get('#sellType').select('sellDiv', { force: true })
        cy.get('#sellScan', { timeout: 30000 }).should('be.visible').type(sku + '{enter}', { timeout: 30000 })
        cy.window().its('data').should('have.length', 1)

        // The screen opens in "Select Customer" mode, so #sellCN lives in a display:none block until
        // the toggle is switched. A sale needs a named customer by default (pos.customer.required is
        // ON, matching the long-standing behaviour), so switch modes rather than force-typing into a
        // hidden field — forcing would prove the sale can be made in a state no cashier can reach.
        cy.get('#btnModeManual').click({ timeout: 30000 })
        cy.get('#sellCN').should('be.visible').type('QP Buyer ' + stamp)
        cy.get('#sellRec').clear().type('40')
        cy.get('#addSell').click({ timeout: 30000 })

        // WAIT for the sale to be written. The first draft only commented that "the sale has to land"
        // and then queried immediately — the ranking would have been read before the row existed, and
        // the test would have failed for a reason that had nothing to do with the ranking.
        cy.wait('@sale').its('response.statusCode').should('eq', 200)

        // Options-object form. `cy.request(a, b)` is (METHOD, url) — passing (url, options) made
        // Cypress read "/topProducts?..." as the HTTP method. The single-argument calls above are
        // the url-only form and are fine as they are.
        cy.request({ url: '/topProducts?days=1&limit=24', timeout: 20000 }).then((r) => {
          const ids = (r.body.collection || []).map((t) => String(t.productId))
          expect(ids, 'the just-sold product is ranked').to.include(String(productId))
        })
      })
  })
})

describe('P3 — OFF (default): nothing appears above the cart', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('the tile panel stays hidden', () => {
    openSell({ quickPick: false })
    cy.get('#quickPickWrap').should('not.be.visible')
  })

  it('Alt+1 does nothing', () => {
    openSell({ quickPick: false, shortcuts: true })
    pressKey('1', true)
    cy.window().its('data').should('have.length', 0)
  })
})

describe('P3 — ON', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  // Each test below stubs /topProducts with a known list rather than using whatever this tenant
  // happens to have sold: the behaviour under test is the TILES, not the ranking — which the
  // endpoint block above covers against real data.

  it('renders a tile per product, with Alt keys on the first nine', () => {
    cy.intercept('GET', '**/topProducts*', {
      body: {
        status: 'SUCCESS',
        collection: [
          { productId: 101, name: 'Tomatoes /kg', sellingPrice: 80, units: 40 },
          { productId: 102, name: 'Naan', sellingPrice: 25, units: 33 }
        ]
      }
    }).as('tiles')
    openSell({ quickPick: true })
    cy.wait('@tiles')
    cy.get('#quickPickWrap').should('be.visible')
    cy.get('.qp-tile').should('have.length', 2)
    cy.get('.qp-tile').first().should('contain', 'Tomatoes /kg').and('contain', 'Alt+1')
  })

  it('a product name is escaped, never injected as markup', () => {
    cy.intercept('GET', '**/topProducts*', {
      body: {
        status: 'SUCCESS',
        collection: [{ productId: 103, name: '<img src=x onerror=alert(1)>Bread', sellingPrice: 10, units: 5 }]
      }
    }).as('tiles')
    openSell({ quickPick: true })
    cy.wait('@tiles')
    // The tag arrived as TEXT — no element was created from the product name.
    cy.get('.qp-tile .qp-name').should('contain', '<img')
    cy.get('.qp-tile img').should('not.exist')
  })

  it('Alt+1 adds the first tile to the cart', () => {
    cy.intercept('GET', '**/topProducts*', {
      body: { status: 'SUCCESS', collection: [{ productId: 201, name: 'Loose Sugar /kg', sellingPrice: 145, units: 60 }] }
    }).as('tiles')
    openSell({ quickPick: true })
    cy.wait('@tiles')
    cy.get('.qp-tile').should('have.length', 1)     // grid drawn => quickPick[] populated

    pressKey('1', true)
    cy.window().its('data').should('have.length', 1)
    cy.window().its('data.0.productId').should('eq', 201)
    cy.window().its('data.0.quantity').should('eq', 1)
    // The tile went through scanAddToCart, so its money is on the line — the SF-12 fix applies here too.
    cy.window().its('data.0.totalAmount').should('not.be.oneOf', [undefined, null, ''])
  })

  it('pressing the same tile twice accumulates on one line', () => {
    cy.intercept('GET', '**/topProducts*', {
      body: { status: 'SUCCESS', collection: [{ productId: 202, name: 'Naan', sellingPrice: 25, units: 90 }] }
    }).as('tiles')
    openSell({ quickPick: true })
    cy.wait('@tiles')
    cy.get('.qp-tile').should('have.length', 1)

    pressKey('1', true)
    cy.window().its('data.0.quantity').should('eq', 1)
    pressKey('1', true)
    cy.window().its('data.0.quantity').should('eq', 2)
    cy.window().its('data').should('have.length', 1)
  })

  it('clicking a tile works too — a touch till has no Alt key', () => {
    cy.intercept('GET', '**/topProducts*', {
      body: { status: 'SUCCESS', collection: [{ productId: 203, name: 'Eggs dozen', sellingPrice: 330, units: 12 }] }
    }).as('tiles')
    openSell({ quickPick: true })
    cy.wait('@tiles')

    cy.get('.qp-tile').first().click({ timeout: 30000 })
    cy.window().its('data.0.productId').should('eq', 203)
  })

  /**
   * Quick pick and the P2 action keys are SEPARATE settings. A shop that turns on quick pick alone
   * must still get Alt+1..9 — otherwise the tiles would advertise "Alt+1" badges that do nothing.
   * (This was a real bug in the first draft: Alt+digit sat behind the shortcuts flag.)
   */
  it('Alt+1 works with quick pick on and the P2 shortcuts OFF', () => {
    cy.intercept('GET', '**/topProducts*', {
      body: { status: 'SUCCESS', collection: [{ productId: 204, name: 'Milk 1L', sellingPrice: 210, units: 21 }] }
    }).as('tiles')
    openSell({ quickPick: true, shortcuts: false })
    cy.wait('@tiles')
    cy.get('.qp-tile').should('have.length', 1)

    pressKey('1', true)
    cy.window().its('data.0.productId').should('eq', 204)
  })

  it('an Alt+digit with no tile behind it is ignored', () => {
    cy.intercept('GET', '**/topProducts*', {
      body: { status: 'SUCCESS', collection: [{ productId: 205, name: 'Salt 800g', sellingPrice: 60, units: 8 }] }
    }).as('tiles')
    openSell({ quickPick: true })
    cy.wait('@tiles')
    // Assert the tile IS there first: without this the test would pass vacuously whenever the grid
    // simply failed to render, which is the opposite of what it claims to prove.
    cy.get('.qp-tile').should('have.length', 1)

    pressKey('7', true)                       // only one tile exists
    cy.window().its('data').should('have.length', 0)
  })

  it('an empty ranking hides the panel rather than showing an empty box', () => {
    cy.intercept('GET', '**/topProducts*', { body: { status: 'SUCCESS', collection: [] } }).as('tiles')
    openSell({ quickPick: true })
    cy.wait('@tiles')
    // A shop with no sales history has no best sellers; a blank grid claiming to be one is worse.
    cy.get('#quickPickWrap').should('not.be.visible')
  })

  it('a failed fetch hides the panel — the tiles are an accelerator, never a gate', () => {
    cy.intercept('GET', '**/topProducts*', { statusCode: 500, body: {} }).as('tiles')
    openSell({ quickPick: true })
    cy.wait('@tiles')
    cy.get('#quickPickWrap').should('not.be.visible')
    // ...and the normal picker still reaches every product.
    cy.get('#sellItemDD').should('exist')
  })

  it('tiles are ignored while a modal is open', () => {
    cy.intercept('GET', '**/topProducts*', {
      body: { status: 'SUCCESS', collection: [{ productId: 206, name: 'Tea 190g', sellingPrice: 520, units: 15 }] }
    }).as('tiles')
    openSell({ quickPick: true })
    cy.wait('@tiles')
    cy.get('.qp-tile').should('have.length', 1)     // a tile exists, so 'ignored' means something

    cy.window().then((w) => { w.$('body').append('<div class="crud-overlay open" id="fakeQp"></div>') })
    pressKey('1', true)
    cy.window().its('data').should('have.length', 0)
    cy.window().then((w) => { w.$('#fakeQp').remove() })
  })
})
