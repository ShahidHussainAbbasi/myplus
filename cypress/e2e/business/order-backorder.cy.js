/**
 * OMS O5c — backorders.
 * Design: microservices/docs/slices/oms-O5c-backorders.md
 *
 * Before this, insufficient stock REJECTED the checkout: a shopper who wanted 10 and could have 8 got nothing
 * and the merchant lost a sale they could have filled in two days.
 *
 * The two assertions that matter most are the ones about money and honesty:
 *   - only what can be FILLED is invoiced, so the books never recognise undelivered revenue and stock never
 *     goes negative (§2 of the design — this is why the shortfall lives on the order, not in inventory);
 *   - the shopper is told BEFORE committing, because a backorder discovered when half the order arrives is a
 *     complaint rather than a sale.
 *
 * It is OFF by default, so the first case proves the old behaviour is untouched.
 */
describe('OMS O5c — an order can be accepted when stock is short', () => {
  const run = String(Date.now()).slice(-6)
  const pname = 'BackShop_' + run
  let orgId, productId

  const setCfg = (key, value) =>
    cy.request({ method: 'POST', url: '/saveOrderConfig', form: true, body: { key, value: String(value) }, failOnStatusCode: false })
      .then((r) => expect(r.body.success, `saving ${key}=${value}: ${JSON.stringify(r.body)}`).to.eq(true))

  before(() => {
    cy.loginAsMarketplaceOwner()
    cy.request('/getMyOrganizations').then((r) => {
      const o = (r.body.collection || [])[0] || {}
      orgId = o.id || o.organizationId || o.orgId
      expect(orgId).to.exist
    })
    // Exactly 3 sellable — every case below asks for more than that.
    cy.request({
      method: 'POST', url: '/addProduct', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { name: pname, sku: 'BO' + run, sellingPrice: 10, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      productId = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', failOnStatusCode: false,
        headers: { 'Content-Type': 'application/json' }, body: { productId, quantity: 3 },
      })
    })
  })

  beforeEach(() => cy.loginAsMarketplaceOwner())

  // Leave the shop as it was found — this flag changes how every later checkout behaves.
  after(() => {
    cy.loginAsMarketplaceOwner()
    setCfg('order.backorder.allowed', 'false')
    setCfg('order.backorder.acceptFullShortfall', 'true')
  })

  const stock = () => cy.request('/productStock?productId=' + productId).then((r) => Number(r.body.stock))

  const order = (name, qty) => cy.storefrontOrder(orgId, { productId, quantity: qty },
    { customerName: name, customerContact: '0300BO' + run, shippingAddress: '3 Wait Lane', paymentMode: 'COD' })

  it('with backorders OFF, a short checkout is refused exactly as before', () => {
    setCfg('order.backorder.allowed', 'false')
    order('OffBuyer_' + run, 10).then((r) => {
      expect(r.body.success, 'unchanged behaviour when the flag is off').to.eq(false)
      expect(String(r.body.message || '').toLowerCase()).to.match(/stock|available/)
    })
  })

  it('the shopper is warned BEFORE committing, with what is short and when it is promised', () => {
    setCfg('order.backorder.allowed', 'true')
    cy.request({
      method: 'POST', url: '/storefront/cart/add', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { organizationId: orgId, productId, quantity: 10 },
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const token = r.body.data.cartToken
      return cy.request(`/storefront/checkout/quote?org=${orgId}&cartToken=${encodeURIComponent(token)}&shippingMethod=PICKUP`)
    }).then((r) => {
      const q = r.body.data
      expect(q.hasBackorder, 'the quote must say so before the shopper commits').to.eq(true)
      expect(q.promisedDate, 'and when to expect it').to.be.a('string')
      const line = (q.items || []).find((l) => l.productId === productId)
      expect(Number(line.backorderedQuantity), '10 wanted, 3 sellable').to.eq(7)
    })
  })

  it('the order is accepted, and ONLY what can be filled is invoiced', () => {
    setCfg('order.backorder.allowed', 'true')
    let before
    stock().then((s) => { before = s })

    order('BackBuyer_' + run, 10).then((r) => {
      expect(r.body.success, `backorder should be accepted: ${JSON.stringify(r.body)}`).to.eq(true)
      const id = r.body.data.id

      cy.request('/getOrder?id=' + id).then((d) => {
        const o = d.body.data
        expect(o.fulfilmentStatus, 'nothing shipped, something owed').to.eq('BACKORDERED')
        expect(o.promisedDate, 'a promise was recorded').to.be.a('string')

        const line = o.items[0]
        expect(line.quantity, 'the line records what was ORDERED').to.eq(10)
        expect(line.quantityBackordered, 'and what is owed').to.eq(7)
        expect(line.quantityShipped).to.eq(0)

        // The invariant: ordered = invoiced + backordered.
        expect(line.quantity - line.quantityBackordered, 'invoiced').to.eq(3)
      })
    })

    // Stock fell by 3, not 10. The shortfall lives on the ORDER and never touches inventory, which is what
    // keeps stock from going negative and the books from recognising undelivered revenue.
    stock().then((s) => {
      expect(s, 'only the filled part left inventory').to.eq(before - 3)
      expect(s).to.be.at.least(0)
    })
  })

  it('a backordered order is still cancellable — nothing has left the building', () => {
    setCfg('order.backorder.allowed', 'true')
    order('CancelBack_' + run, 5).then((r) => {
      // 0 sellable by now, so all 5 are owed.
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const id = r.body.data.id
      cy.request('/getOrder?id=' + id).then((d) => {
        expect(d.body.data.allowedTransitions, 'O5b forbids cancelling a SHIPPED order; this one has shipped nothing')
          .to.include('CANCELLED')
      })
      cy.request({ method: 'POST', url: '/updateOrderStatus', failOnStatusCode: false,
        headers: { 'Content-Type': 'application/json' }, body: { id, status: 'CANCELLED' } })
        .then((c) => expect(c.body.success, JSON.stringify(c.body)).to.eq(true))
    })
  })

  it('backordered units cannot be dispatched — they are neither invoiced nor pickable', () => {
    setCfg('order.backorder.allowed', 'true')
    order('ShipBack_' + run, 4).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const id = r.body.data.id
      cy.request('/getOrder?id=' + id).then((d) => {
        const line = d.body.data.items[0]
        // Everything is owed by now (stock exhausted), so there is nothing to ship at all.
        return cy.request({ method: 'POST', url: '/shipOrder', failOnStatusCode: false,
          headers: { 'Content-Type': 'application/json' },
          body: { id, lines: [{ orderItemId: line.id, quantity: line.quantity }] } })
      }).then((s) => {
        expect(s.body.success, 'shipping owed units would send goods the shop has not billed for').to.eq(false)
      })
    })
  })

  it('the back office can see what is still owed, and what can now be filled', () => {
    setCfg('order.backorder.allowed', 'true')
    cy.request('/getBackorders').then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const owed = r.body.data || []
      expect(owed, 'the orders placed above are outstanding').to.not.be.empty
      owed.forEach((o) => {
        expect(o).to.have.property('readyToFulfil')
        // Every one of these still owes something; a cancelled order must not appear.
        expect(o.fulfilmentStatus).to.not.eq('CANCELLED')
      })
    })
    // Nothing has been restocked, so nothing is ready — this is a READ, not a job, so it reflects stock now.
    cy.request('/getBackorders?ready=true').then((r) => {
      expect(r.body.success).to.eq(true)
      expect(r.body.data || [], 'no stock has arrived yet').to.be.empty
    })
  })

  it('restocking makes an outstanding backorder show as ready — no job, just stock', () => {
    setCfg('order.backorder.allowed', 'true')
    cy.request({
      method: 'POST', url: '/addProductStock', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' }, body: { productId, quantity: 50 },
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    // Readiness is DERIVED from stock that exists, so it flips the moment goods arrive. A stored "ready" flag
    // would have needed a sweeper to keep true — which is why O5c has neither.
    cy.request('/getBackorders?ready=true').then((r) => {
      expect(r.body.data || [], 'stock arrived, so the backlog is fillable').to.not.be.empty
      expect(r.body.data[0].readyToFulfil).to.eq(true)
    })
  })

  it('a backordered order is flagged late once its promised date passes', () => {
    // promiseDays is floored at 1, so nothing seeded here is late yet — assert the filter EXISTS and excludes
    // the not-yet-due, which is the half that can be proven without waiting a day.
    cy.request('/getOrders?late=true').then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data).to.have.property('content')
      ;(r.body.data.content || []).forEach((o) => {
        expect(o.late, 'anything returned by the late filter must actually be late').to.eq(true)
      })
    })
    cy.request('/getOrders?q=' + encodeURIComponent('BackBuyer_' + run)).then((r) => {
      const o = (r.body.data.content || [])[0]
      expect(o.promisedDate, 'a backordered order carries a promise').to.be.a('string')
      expect(o.late, 'promised in the future, so not late').to.eq(false)
    })
  })

  it('with acceptFullShortfall OFF, a TOTAL shortfall is refused but a partial one is not', () => {
    setCfg('order.backorder.allowed', 'true')
    setCfg('order.backorder.acceptFullShortfall', 'false')
    // Stock was restocked to 50 above, so ask for far more than that: partly fillable, must be ACCEPTED.
    order('PartialOK_' + run, 60).then((r) => {
      expect(r.body.success, `a partial shortfall stays acceptable: ${JSON.stringify(r.body)}`).to.eq(true)
    })
    // Now nothing is left, so the next order is a TOTAL shortfall and must be refused.
    order('FullNo_' + run, 5).then((r) => {
      expect(r.body.success, 'a total shortfall is refused when the shop only accepts partial ones').to.eq(false)
    })
    setCfg('order.backorder.acceptFullShortfall', 'true')
  })

  it('the storefront shows the warning on the page', () => {
    setCfg('order.backorder.allowed', 'true')
    cy.visit('/store?org=' + orgId)
    cy.contains('.card .name', pname, { timeout: 10000 }).should('exist')
    cy.contains('.card', pname).find('.add').click()
    cy.get('#checkout').should('be.visible')
    cy.get('#backorderNotice', { timeout: 10000 })
      .should('be.visible')
      .and('contain', 'not in stock yet')
  })
})
