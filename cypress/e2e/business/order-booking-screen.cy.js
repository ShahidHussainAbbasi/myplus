/**
 * OMS O7 D2b — the order booker's SCREEN.
 * Design: microservices/docs/slices/oms-O7-distribution-presales.md §10
 *
 * D1 and D2 built a complete pre-sales API that no field rep could reach. This is the screen they actually
 * use: pick the shop, see what it owes BEFORE writing anything, compose lines, book, and see what happened to
 * the orders they took earlier.
 *
 * These are UI cases on purpose — the endpoints are already gated by `order-approval` and `order-booker`.
 * What is unproven until now is whether a human can drive them, which is the exact gap that has bitten this
 * programme five times (O3's setting, O4's endpoints, O5d's policy, and both O7 reads).
 */
describe('OMS O7 D2b — a rep can actually book an order', () => {
  const run = String(Date.now()).slice(-6)
  const OUTLET = 'ScreenOutlet ' + run
  const PRODUCT = 'ScreenProd_' + run

  before(() => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name: PRODUCT, sku: 'SC' + run, sellingPrice: 60, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      return cy.request({
        method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId: r.body.data.id, quantity: 100 }, failOnStatusCode: false,
      })
    })
    // An outlet WITH a limit, so the credit banner has something to say.
    cy.request({
      method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name: OUTLET, contact: '0300' + run, creditLimit: 5000 },
    }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
  })

  beforeEach(() => {
    cy.loginAsOrderBooker()
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'showOrderBooking')
    cy.window().then((w) => w.showOrderBooking())
    cy.get('#BookingDiv').should('be.visible')
  })

  it('the screen loads with the shop and product pickers populated', () => {
    // The pickers read the shared masters — a booking screen that could not see the catalog would be the
    // sixth instance of a capability shipped without a way to use it.
    cy.get('#bkOutlet option').should('have.length.greaterThan', 1)
    cy.get('#bkProduct option').should('have.length.greaterThan', 1)
    cy.get('#bkSubmit').should('be.disabled')   // nothing composed yet
  })

  it('picking the shop shows its credit standing BEFORE any line is entered', () => {
    // Finding B3, and the reason the screen is laid out in this order: the rep learns the shop is over its
    // limit while still at the counter, not from a rejection the next day.
    cy.get('#bkCredit').should('not.be.visible')
    cy.get('#bkOutlet').select(OUTLET)
    cy.get('#bkCredit').should('be.visible').and('contain', '5000')
  })

  it('selecting a product pre-fills its price, and the rep may overwrite it', () => {
    cy.get('#bkProduct').select(PRODUCT)
    cy.get('#bkPrice').should('have.value', '60')
    // The agreed price is the rep's to set — that is what D-3 lets the warehouse amend later, not replace.
    cy.get('#bkPrice').clear().type('55')
    cy.get('#bkQty').type('4')
    cy.get('#bkAddBtn').click()
    cy.get('#bkLinesBody tr').should('have.length', 1)
    cy.get('#bkTotal').should('have.text', '220.00')
  })

  it('adding the same product again REPLACES the quantity rather than stacking a second line', () => {
    // A rep correcting themselves at the counter means "make it six", not "six more". Getting this wrong
    // double-orders the shop, which is the single most expensive mistake this screen could make.
    cy.get('#bkProduct').select(PRODUCT)
    cy.get('#bkQty').type('4')
    cy.get('#bkAddBtn').click()
    cy.get('#bkProduct').select(PRODUCT)
    cy.get('#bkQty').type('6')
    cy.get('#bkAddBtn').click()
    cy.get('#bkLinesBody tr').should('have.length', 1)
    cy.get('#bkLinesBody tr td').eq(1).should('have.text', '6')
  })

  it('a line can be removed, and Book is disabled with an empty order', () => {
    cy.get('#bkProduct').select(PRODUCT)
    cy.get('#bkQty').type('2')
    cy.get('#bkAddBtn').click()
    cy.get('#bkSubmit').should('not.be.disabled')
    cy.get('#bkLinesBody .btn-danger').click()
    cy.get('#bkLinesBody tr').should('have.length', 0)
    cy.get('#bkSubmit').should('be.disabled')
  })

  it('THE PAYOFF — booking from the screen creates a PENDING_APPROVAL order, and the rep sees it', () => {
    cy.intercept('POST', '/bookOrder').as('book')
    cy.get('#bkOutlet').select(OUTLET)
    cy.get('#bkProduct').select(PRODUCT)
    cy.get('#bkQty').type('3')
    cy.get('#bkAddBtn').click()
    cy.get('#bkSubmit').click()

    cy.wait('@book').then((i) => {
      expect(i.response.body.success, JSON.stringify(i.response.body)).to.eq(true)
      expect(i.response.body.data.fulfilmentStatus).to.eq('PENDING_APPROVAL')
      expect(i.response.body.data.orderNo).to.match(/^SO-\d+$/)
      // The screen composes an idempotency key per attempt — a rep on bad wifi pressing Book twice must not
      // double-order the shop.
      expect(i.request.body.idempotencyKey, 'the retry key is sent').to.match(/^BK-/)
    })

    // The order clears and the rep's own list picks it up, on the same screen — "what happened to the order I
    // took here?" is asked at the counter.
    cy.get('#bkLinesBody tr').should('have.length', 0)
    cy.get('#bkMyOrdersBody tr').should('have.length.greaterThan', 0)
    cy.contains('#bkMyOrdersBody tr', OUTLET).should('contain', 'PENDING_APPROVAL')
  })

  it('a rejection shows its REASON on the rep\'s own list', () => {
    // The only thing that lets a rep act on a rejection. Booked here, rejected by the warehouse, read back on
    // the rep's screen — the full loop the founding requirement describes.
    const label = 'RejectedOnScreen ' + run
    // Booked through the API rather than the form: this case is about what the rep READS afterwards, and
    // driving the form again would only re-prove the previous case.
    cy.request('/catalogProducts?size=2000').then((r) => {
      const list = (r.body.data && r.body.data.content) || []
      const p = list.find((x) => x.name === PRODUCT)
      return cy.request({
        method: 'POST', url: '/bookOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        body: {
          customerName: label, customerContact: '0300' + run,
          items: [{ productId: p.id, quantity: 2, price: 60, productName: PRODUCT }],
        },
      })
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const id = r.body.data.id
      cy.loginAsMarketplaceOwner()
      cy.request({
        method: 'POST', url: '/rejectOrder', headers: { 'Content-Type': 'application/json' },
        body: { id, reason: 'Shop already owes too much' }, failOnStatusCode: false,
      }).then((s) => expect(s.body.success, JSON.stringify(s.body)).to.eq(true))
    })

    cy.loginAsOrderBooker()
    cy.visit('/businessDashboard')
    cy.window().then((w) => w.showOrderBooking())
    cy.contains('#bkMyOrdersBody tr', label)
      .should('contain', 'REJECTED')
      .and('contain', 'Shop already owes too much')
  })
})
