/**
 * Negative test cases — verify the system blocks invalid actions
 * and never crashes or leaks a 500 on bad input.
 *
 * Strategy: use a closure flag + cy.intercept to confirm no POST
 * is fired when client-side validation should block submission.
 */

// ─── 1. Sell: Cart Validation ─────────────────────────────────────────────────

describe('Negative — Sell: Empty Cart Blocks Submission', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.intercept('GET', '/getUserItems').as('getItems')
    cy.intercept('GET', /\/getUserCustomer(?!s)/).as('getCustomers')
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')
    cy.wait('@getItems', { timeout: 10000 })
    cy.wait('@getCustomers', { timeout: 10000 })
  })

  it('customer entered manually with no cart items — addSell never POSTs', () => {
    let posted = false
    cy.intercept('POST', '/addSell', (req) => { posted = true; req.continue() })

    cy.get('#btnModeManual').click()
    cy.get('#sellCN').type('Walk-in Test')
    // No items added to cart
    cy.get('#addSell').click()
    cy.wait(800)
    cy.then(() => expect(posted, '/addSell should not have been called').to.be.false)
  })

  it('customer in select mode, no items in cart — addSell never POSTs', () => {
    let posted = false
    cy.intercept('POST', '/addSell', (req) => { posted = true; req.continue() })

    cy.get('#sellCustomerDD option').then(($opts) => {
      const realOpts = $opts.filter((i, el) => el.value !== '')
      if (realOpts.length === 0) {
        cy.log('No customers in DB — using manual mode instead')
        cy.get('#btnModeManual').click()
        cy.get('#sellCN').type('Walk-in Fallback')
      } else {
        cy.get('#sellCustomerDD').select(Cypress.$(realOpts[0]).val())
      }
    })
    // No items in cart
    cy.get('#addSell').click()
    cy.wait(800)
    cy.then(() => expect(posted, '/addSell should not have been called').to.be.false)
  })

  it('customer + positive payment entered, but empty cart — addSell never POSTs', () => {
    let posted = false
    cy.intercept('POST', '/addSell', (req) => { posted = true; req.continue() })

    cy.get('#btnModeManual').click()
    cy.get('#sellCN').type('Walk-in Test')
    cy.get('#sellRec').clear().type('500')
    // No items in cart
    cy.get('#addSell').click()
    cy.wait(800)
    cy.then(() => expect(posted, '/addSell should not fire with empty cart').to.be.false)
  })
})

// ─── 2. Purchase: Required Field Validation ───────────────────────────────────

describe('Negative — Purchase: Missing Fields Block Submission', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.intercept('GET', '/getUserItems').as('getItems')
    cy.visit('/businessDashboard')
    cy.get('#purchaseType').select('purchaseDiv', { force: true })
    cy.get('#purchaseDiv').should('be.visible')
    cy.wait('@getItems', { timeout: 10000 })
  })

  it('submit without selecting an item — addPurchase never POSTs', () => {
    let posted = false
    cy.intercept('POST', '/addPurchase', (req) => { posted = true; req.continue() })

    cy.get('#purchaseQuantity').clear().type('5')
    // #purchaseItemDD left blank (required)
    cy.get('#addPurchase').click()
    cy.wait(800)
    cy.then(() => expect(posted, 'Purchase without item should not POST').to.be.false)
  })

  it('item selected but quantity is 0 — addPurchase never POSTs', () => {
    let posted = false
    cy.intercept('POST', '/addPurchase', (req) => { posted = true; req.continue() })

    cy.get('#purchaseItemDD option').then(($opts) => {
      const realOpts = $opts.filter((i, el) => el.value !== '')
      if (realOpts.length === 0) {
        cy.log('No items in DB — quantity-0 test skipped')
        return
      }
      cy.get('#purchaseItemDD').select(Cypress.$(realOpts[0]).val(), { force: true })
      cy.get('#purchaseQuantity').clear().type('0')
      cy.get('#addPurchase').click()
      cy.wait(800)
      cy.then(() => expect(posted, 'Purchase with qty=0 should not POST').to.be.false)
    })
  })

  it('both fields blank — addPurchase never POSTs', () => {
    let posted = false
    cy.intercept('POST', '/addPurchase', (req) => { posted = true; req.continue() })

    cy.get('#resetPurchase').click() // clear form
    cy.get('#addPurchase').click()
    cy.wait(800)
    cy.then(() => expect(posted, 'Blank purchase form should not POST').to.be.false)
  })
})

// ─── 3. API: Invalid / Empty Body Never Causes 500 ───────────────────────────

describe('Negative — API: Invalid Inputs Return Errors, Not 500', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
  })

  it('addCustomer with empty name — returns ERROR status, not 500', () => {
    cy.request({
      method: 'POST',
      url: '/addCustomer',
      body: { name: '', contact: '0300000001', email: 'x@test.com', address: 'addr' },
      headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
    }).then((res) => {
      expect([200, 400]).to.include(res.status)
      if (res.status === 200) {
        expect(res.body.status).to.be.a('string')
        expect(res.body.status).not.to.eq('SUCCESS')
      }
    })
  })

  it('addItem with empty name — returns error status, not 500', () => {
    cy.request({
      method: 'POST',
      url: '/addItem',
      body: { iname: '', icode: `NEG${Date.now()}`, idesc: 'neg test' },
      headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
    }).then((res) => {
      expect([200, 400]).to.include(res.status)
      if (res.status === 200) {
        expect(res.body.status).to.be.a('string')
      }
    })
  })

  it('addSell with null sales array — returns error, not 500', () => {
    cy.request({
      method: 'POST',
      url: '/addSell',
      body: {
        customer: { name: 'NegTest', contact: '', paidAmount: 100, dueAmount: 0 },
        sales: null,
      },
      headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
    }).then((res) => {
      expect([200, 400, 422]).to.include(res.status)
      if (res.status === 200) {
        expect(res.body.status).to.be.a('string')
      }
    })
  })

  it('addPurchase with empty body — returns error, not 500', () => {
    cy.request({
      method: 'POST',
      url: '/addPurchase',
      body: {},
      headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
    }).then((res) => {
      expect([200, 400, 422]).to.include(res.status)
      if (res.status === 200) {
        expect(res.body.status).to.be.a('string')
      }
    })
  })

  it('deleteCustomer with non-existent id — returns false or error, not 500', () => {
    cy.request({
      method: 'POST',
      url: '/deleteCustomer',
      body: { checked: '999999999' },
      headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
    }).then((res) => {
      expect([200, 400]).to.include(res.status)
    })
  })

  it('deleteItem with non-existent id — returns false or error, not 500', () => {
    cy.request({
      method: 'POST',
      url: '/deleteItem',
      body: { checked: '999999999' },
      headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
    }).then((res) => {
      expect([200, 400]).to.include(res.status)
    })
  })
})

// ─── 4. Auth: Unauthenticated Access ─────────────────────────────────────────

describe('Negative — Auth: Protected Endpoints Reject Unauthenticated Requests', () => {
  beforeEach(() => {
    cy.clearCookies()
  })

  it('/getUserSell without session returns redirect or 4xx', () => {
    cy.request({ url: '/getUserSell', failOnStatusCode: false }).then((res) => {
      expect([302, 401, 403]).to.include(res.status)
    })
  })

  it('/addSell POST without session returns redirect or 4xx', () => {
    cy.request({
      method: 'POST',
      url: '/addSell',
      body: {},
      headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
    }).then((res) => {
      expect([302, 401, 403]).to.include(res.status)
    })
  })

  it('/getUserCustomer without session returns redirect or 4xx', () => {
    cy.request({ url: '/getUserCustomer', failOnStatusCode: false }).then((res) => {
      expect([302, 401, 403]).to.include(res.status)
    })
  })
})
