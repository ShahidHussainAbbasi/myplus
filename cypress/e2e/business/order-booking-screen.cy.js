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
  const OUTLET_B = 'BookOutletB_' + run
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
      // An ADDRESS too: the screen fills contact and delivery address from the shop record, and a fixture
      // with no address could not tell "filled correctly" from "left blank".
      body: { name: OUTLET, contact: '0300' + run, address: '12 Mall Road, Lahore', creditLimit: 5000 },
    }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    // A SECOND outlet with DIFFERENT details. Without one, "the details fill" cannot be told apart from
    // "the details filled once and then stuck" — which is exactly the defect this fixture now catches.
    cy.request({
      method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name: OUTLET_B, contact: '0311' + run, address: '9 Khan Pur Road', creditLimit: 5000 },
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
    cy.get('#bkOutlet').select(OUTLET, { force: true })
    cy.get('#bkCredit').should('be.visible').and('contain', '5000')
  })

  it('selecting a product pre-fills its price, and the rep may overwrite it', () => {
    cy.get('#bkProduct').select(PRODUCT, { force: true })
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
    cy.get('#bkProduct').select(PRODUCT, { force: true })
    cy.get('#bkQty').type('4')
    cy.get('#bkAddBtn').click()
    cy.get('#bkProduct').select(PRODUCT, { force: true })
    cy.get('#bkQty').type('6')
    cy.get('#bkAddBtn').click()
    cy.get('#bkLinesBody tr').should('have.length', 1)
    cy.get('#bkLinesBody tr td').eq(1).should('have.text', '6')
  })

  it('a line can be removed, and Book is disabled with an empty order', () => {
    cy.get('#bkProduct').select(PRODUCT, { force: true })
    cy.get('#bkQty').type('2')
    cy.get('#bkAddBtn').click()
    cy.get('#bkSubmit').should('not.be.disabled')
    cy.get('#bkLinesBody .btn-danger').click()
    cy.get('#bkLinesBody tr').should('have.length', 0)
    cy.get('#bkSubmit').should('be.disabled')
  })

  it('THE PAYOFF — booking from the screen creates a PENDING_APPROVAL order, and the rep sees it', () => {
    cy.intercept('POST', '/bookOrder').as('book')
    cy.get('#bkOutlet').select(OUTLET, { force: true })
    cy.get('#bkProduct').select(PRODUCT, { force: true })
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

  it('THE BOOKS CASE — dispatching bills the OUTLET the rep booked, not a duplicate of it', () => {
    // Caught by auditing D2b: the order recorded the buyer as a NAME only. At dispatch, business-service
    // resolves the buyer by Query-By-Example on name + contact + THE ACTING USER — and since the outlet was
    // created by the owner while the dispatch runs as the warehouse admin, the probe matched nothing and
    // created a SECOND outlet: no credit limit, its own balance, the receivable split across two rows, and the
    // credit standing shown at the counter applying to an account the invoice never touched.
    //
    // The assertion that matters is the DUPLICATE COUNT. "An invoice exists" passes under the bug too.
    // Counted through /outlets, NOT getUserCustomer: this block runs as the BOOKER, and the audit-scoped read
    // correctly returns them nothing (that is D2d's whole point, asserted in order-booker.cy.js). Using it here
    // would fail for a reason unrelated to what this case is testing.
    let before, orderId, outletId
    cy.request('/outlets').then((r) => {
      var rows = ((r.body.collection || r.body.data) || []).filter((c) => c.name === OUTLET)
      before = rows.length
      expect(before, 'positive control: the outlet exists exactly once before we start').to.eq(1)
      outletId = rows[0].id
    })

    // Book through the SCREEN, so this proves what the rep's own workflow produces.
    cy.get('#bkOutlet').select(OUTLET, { force: true })
    cy.get('#bkProduct').select(PRODUCT, { force: true })
    cy.get('#bkQty').type('2')
    cy.get('#bkAddBtn').click()
    cy.intercept('POST', '/bookOrder').as('book2')
    cy.get('#bkSubmit').click()
    cy.wait('@book2').then((i) => {
      expect(i.request.body.customerId, 'the screen sends WHICH outlet').to.be.a('number')
      orderId = i.response.body.data.id
    })

    // Confirm + dispatch as the warehouse — a DIFFERENT user from whoever created the outlet, which is the
    // whole condition that made the probe fail.
    cy.then(() => cy.loginAsMarketplaceOwner())
    cy.then(() => cy.request({
      method: 'POST', url: '/confirmOrder', headers: { 'Content-Type': 'application/json' },
      body: { id: orderId }, failOnStatusCode: false,
    })).then((s) => expect(s.body.success, JSON.stringify(s.body)).to.eq(true))
    cy.then(() => cy.request('/getOrder?id=' + orderId)).then((r) => {
      const line = r.body.data.items[0]
      return cy.request({
        method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
        body: { id: orderId, lines: [{ orderItemId: line.id, quantity: 2 }], carrier: 'Ahsan' },
      })
    }).then((s) => expect(s.body.success, JSON.stringify(s.body)).to.eq(true))

    // THE assertion: still exactly one outlet of that name. Under the bug this is 2.
    cy.request('/getUserCustomer').then((r) => {
      const rows = ((r.body.collection || r.body.data) || []).filter((c) => c.name === OUTLET)
      expect(rows.length, 'dispatch must NOT have created a duplicate outlet').to.eq(before)
    })
    // And the invoice landed on THAT account, not a fresh one: the outlet the rep picked now owes money.
    // Stronger than checking the row's credit limit, which would still read 5000 on the original row even if
    // the invoice had gone to a duplicate — i.e. it would pass under the bug.
    cy.then(() => cy.request('/creditStanding?customerId=' + outletId)).then((r) => {
      expect(r.body.object, 'the picked outlet has a standing').to.not.be.null
      expect(Number(r.body.object.owed), 'the dispatch billed THIS account').to.be.greaterThan(0)
    })
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

  it('choosing the shop fills its contact and delivery address', () => {
    // A rep re-typing details the system already holds is slower and less accurate than the record — and a
    // mistyped address on a field order is a van at the wrong door. /outlets already returns both alongside
    // the name, so this costs no extra request.
    cy.get('#bkContact').should('have.value', '')
    cy.get('#bkAddress').should('have.value', '')

    cy.get('#bkOutlet').select(OUTLET, { force: true })

    cy.get('#bkContact').should('have.value', '0300' + run)
    cy.get('#bkAddress').should('have.value', '12 Mall Road, Lahore')
  })

  it('⭐ switching shops replaces the details — the first shop must not stick', () => {
    /*
     * THE CASE THAT MATTERS, and the one my first implementation failed.
     *
     * Guarding the fill on "is the box empty?" meant the details filled once and then never again: switching
     * from one shop to another left the FIRST shop's phone number and address against the second shop's
     * order. A van at the wrong door — the precise failure this feature exists to prevent.
     *
     * The rule is "did the shop change?", so this asserts both directions: A → B, then B → A.
     */
    cy.get('#bkOutlet').select(OUTLET, { force: true })
    cy.get('#bkContact').should('have.value', '0300' + run)
    cy.get('#bkAddress').should('have.value', '12 Mall Road, Lahore')

    cy.get('#bkOutlet').select(OUTLET_B, { force: true })
    cy.get('#bkContact').should('have.value', '0311' + run)
    cy.get('#bkAddress').should('have.value', '9 Khan Pur Road')

    // And back again — a one-way fill would pass the check above and still be broken.
    cy.get('#bkOutlet').select(OUTLET, { force: true })
    cy.get('#bkContact').should('have.value', '0300' + run)
    cy.get('#bkAddress').should('have.value', '12 Mall Road, Lahore')
  })

  it('re-selecting the SAME shop keeps a one-off address the rep typed', () => {
    // The other half of the rule. "Send it to the new branch this week" is ordinary, and the order carries
    // its own address precisely so it can differ from the customer master.
    cy.get('#bkOutlet').select(OUTLET, { force: true })
    cy.get('#bkAddress').clear().type('Warehouse gate, Ferozepur Road')

    cy.get('#bkOutlet').select(OUTLET, { force: true })   // the same shop, again

    cy.get('#bkAddress', { timeout: 10000 })
      .should('have.value', 'Warehouse gate, Ferozepur Road')
  })

})
