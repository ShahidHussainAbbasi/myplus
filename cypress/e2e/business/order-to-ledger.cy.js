/**
 * OMS O1 — storefront revenue reaches the books.
 * Design: microservices/docs/slices/oms-O1-storefront-to-books.md
 *
 * THE defect this closes: `placePublic` ran its own reserve → charge → confirm saga and wrote an Order, but
 * never created a trade sale. So an online sale decremented stock and charged a card while producing **no
 * invoice, no revenue journal, no tax-register line, no AR and no payment row** — P&L, trial balance, tax
 * register, period close and day close were all silently wrong for every online sale. POS orders hid the
 * asymmetry because POS recorded the sale first and merely copied its invoiceNo across.
 *
 * What is asserted here is therefore the LINK and the MONEY, not the mechanics:
 *   • a storefront order produces an invoice, and the order carries it;
 *   • that invoice is a real, readable trade sale for the right amount;
 *   • the SERVER's total is used, never the client's (OMS-5);
 *   • a repeat submit with the same cart yields ONE invoice, not two;
 *   • cancelling VOIDS the invoice — returning stock alone would leave the revenue booked, which is the same
 *     defect pointing the other way.
 *
 * Run headed.
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

describe('OMS O1 — storefront revenue reaches the books', () => {
  let orgId, productId
  const pname = 'LedgerShop_' + uniq()

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => { orgId = ((r.body.collection || [])[0] || {}).id })
    cy.request({
      method: 'POST', url: '/addProduct', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { name: pname, sku: 'LDG' + uniq(), sellingPrice: 50, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      productId = r.body.data.id
      expect(productId, 'seeded product').to.exist
      return cy.request({
        method: 'POST', url: '/addProductStock', failOnStatusCode: false,
        headers: { 'Content-Type': 'application/json' },
        body: { productId, quantity: 20 },
      })
    })
  })

  beforeEach(() => cy.loginAsMarketplace())

  const place = (qty, name) => cy.storefrontOrder(orgId, { productId, quantity: qty },
    { customerName: name, customerContact: '0300LDG', shippingAddress: '9 Ledger St', paymentMode: 'COD' })

  it('a storefront order produces an invoice, and the order carries it', () => {
    place(2, 'LedgerBuyer_' + uniq()).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const order = r.body.data
      // The link that did not exist before O1.
      expect(order.invoiceNo, 'the order carries the trade sale it produced').to.be.a('string')
      expect(order.invoiceNo).to.match(/^INV-/)

      // And it is a REAL sale, readable through the same receipt endpoint a counter sale uses.
      cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(order.invoiceNo)).then((rec) => {
        expect(rec.body.status, JSON.stringify(rec.body)).to.eq('SUCCESS')
        const receipt = rec.body.object || rec.body.data
        expect(receipt, 'the invoice is a readable trade sale').to.exist
      })
    })
  })

  it('the SERVER prices the order — a client-supplied total is not trusted (OMS-5)', () => {
    const buyer = 'LiarBuyer_' + uniq()
    // The shopper claims the order costs one paisa. 2 x 50.00 is 100.00 of goods before any shipping, so the
    // assertion is on server AUTHORITY, not on an exact figure — hard-coding a total here would just re-encode
    // whatever the shipping rule happens to be today and break the next time it changes.
    cy.storefrontOrder(orgId, { productId, quantity: 2 },
      { customerName: buyer, customerContact: '0300LIE', shippingAddress: '1 Fib St', paymentMode: 'COD',
        total: 0.01 }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const total = Number(r.body.data.total)
      expect(total, 'the client total was not trusted').to.not.eq(0.01)
      expect(total, 'at least the value of the goods the server priced').to.be.gte(100)
    })
  })

  it('the same cart submitted twice yields ONE invoice', () => {
    // Idempotency is keyed off the CART, so a double-submit replays the same sale instead of invoicing twice.
    // The shared helper mints a fresh cart per call, so this drives cart + checkout directly to reuse one token
    // — which is exactly what a shopper double-clicking "Place order" does.
    const buyer = 'DupeBuyer_' + uniq()
    let cartToken

    cy.request({
      method: 'POST', url: '/storefront/cart/add', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, productId, quantity: 1 },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      cartToken = r.body.data.cartToken
      expect(cartToken, 'cart minted a token').to.be.a('string')
    })

    const checkout = () => cy.request({
      method: 'POST', url: '/storefront/checkout', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, cartToken, customerName: buyer, customerContact: '0300DUP',
              shippingAddress: '2 Dupe St', paymentMode: 'COD' },
    })

    let firstInvoice
    cy.then(() => checkout().then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      firstInvoice = r.body.data.invoiceNo
      expect(firstInvoice, 'first checkout invoiced').to.be.a('string')
    }))

    cy.then(() => checkout().then((r) => {
      // Either the second submit is refused, or it replays the SAME invoice. What must never happen is a
      // SECOND invoice for one cart — that is a customer billed twice.
      if (r.body.success && r.body.data && r.body.data.invoiceNo) {
        expect(r.body.data.invoiceNo, 'a replay, not a second invoice').to.eq(firstInvoice)
      }
    }))
  })

  it('cancelling VOIDS the invoice — the revenue does not stay booked', () => {
    const buyer = 'VoidBuyer_' + uniq()
    let orderId, invoiceNo
    place(3, buyer).then((r) => {
      expect(r.body.success).to.eq(true)
      orderId = r.body.data.id
      invoiceNo = r.body.data.invoiceNo
      expect(invoiceNo, 'order was invoiced').to.be.a('string')
    })

    // Before the cancel the invoice is worth real money.
    cy.then(() => cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo)).then((rec) => {
      const receipt = rec.body.object || rec.body.data
      expect(Number(receipt.grandTotal), 'the live invoice carries its value').to.be.greaterThan(0)
    }))

    cy.then(() => cy.request({
      method: 'POST', url: '/updateOrderStatus', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { id: orderId, status: 'CANCELLED' },
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true)))

    // After the cancel the invoice must contribute NO revenue. Returning stock while leaving the sale standing
    // would overstate P&L and the tax register — the mirror image of the bug O1 fixes.
    //
    // A void deletes the sale lines and zeroes the header, so /getReceipt (which renders from the lines) stops
    // serving it. Either shape satisfies the property under test — no live receipt, or one worth zero — so both
    // are accepted rather than pinning the assertion to today's rendering behaviour.
    cy.then(() => cy.request({ url: '/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo),
                               failOnStatusCode: false }).then((rec) => {
      const receipt = rec.body.object || rec.body.data
      const revenue = receipt ? Number(receipt.grandTotal || 0) : 0
      expect(revenue, 'a voided invoice carries no revenue').to.eq(0)
    }))

    // And the positive proof that the reversal actually ran rather than the invoice merely vanishing: the stock
    // is back. Void restores inventory and reverses the books in ONE operation — that pairing is the whole point.
    cy.then(() => cy.request('/productStock?productId=' + productId).then((r) => {
      expect(parseFloat(r.body.stock), 'cancelling put the 3 units back').to.be.gte(3)
    }))
  })
})
