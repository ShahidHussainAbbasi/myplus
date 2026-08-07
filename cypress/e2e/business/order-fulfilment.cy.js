/**
 * OMS O5b — partial and split shipments.
 * Design: microservices/docs/slices/oms-O5b-shipments.md
 *
 * Before this, an order shipped whole or not at all: `OrderItem` recorded `quantity` with nowhere to say
 * "3 of 5 shipped", and SHIPPED was a word a packer typed with nothing recording what left, when, or with what
 * tracking.
 *
 * The turn of the slice is that **shipping progress is DERIVED from line quantities**. So the assertions that
 * matter are the ones proving the header cannot be moved independently of its parcels: PUT /status refuses
 * SHIPPED outright, and the status only changes because a shipment was recorded.
 */
describe('OMS O5b — an order ships in parts (partial + split + tracking)', () => {
  let orgId, productA, productB
  const run = String(Date.now()).slice(-6)
  const nameA = 'ShipA_' + run
  const nameB = 'ShipB_' + run

  let order          // { id, orderNo }
  let lineA, lineB   // order line ids

  before(() => {
    cy.loginAsMarketplaceOwner()
    cy.request('/getMyOrganizations').then((r) => {
      const o = (r.body.collection || [])[0] || {}
      orgId = o.id || o.organizationId || o.orgId
      expect(orgId, 'marketplace owner must have an organization').to.exist
    })

    const make = (name, sku) => cy.request({
      method: 'POST', url: '/addProduct', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' },
      body: { name, sku, sellingPrice: 10, taxRate: 0, unit: 'pcs' },
    }).then((r) => {
      expect(r.body.success, `addProduct ${name} failed: ${JSON.stringify(r.body)}`).to.eq(true)
      const id = r.body.data.id
      return cy.request({
        method: 'POST', url: '/addProductStock', failOnStatusCode: false,
        headers: { 'Content-Type': 'application/json' }, body: { productId: id, quantity: 50 },
      }).then(() => id)
    })

    make(nameA, 'SHA' + run).then((id) => { productA = id })
    make(nameB, 'SHB' + run).then((id) => { productB = id })

    // One order, two lines: 5 of A and 2 of B — enough to ship partially, split a line, and finish.
    cy.then(() => cy.storefrontOrder(orgId, [{ productId: productA, quantity: 5 }, { productId: productB, quantity: 2 }], {
      customerName: 'ShipBuyer_' + run, customerContact: '0300SHIP' + run,
      shippingAddress: '8 Dispatch Way', paymentMode: 'COD',
    })).then((r) => {
      expect(r.body.success, `order failed: ${JSON.stringify(r.body)}`).to.eq(true)
      order = r.body.data
    })
    cy.then(() => cy.request('/getOrder?id=' + order.id)).then((r) => {
      const items = r.body.data.items
      lineA = (items.find((l) => l.productName === nameA) || {}).id
      lineB = (items.find((l) => l.productName === nameB) || {}).id
      expect(lineA, 'line A must have an id — shipping is requested per line').to.exist
      expect(lineB).to.exist
    })
  })

  beforeEach(() => cy.loginAsMarketplaceOwner())

  const detail = () => cy.request('/getOrder?id=' + order.id).then((r) => {
    expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
    return r.body.data
  })

  const ship = (lines, extra = {}) => cy.request({
    method: 'POST', url: '/shipOrder', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: Object.assign({ id: order.id, lines }, extra),
  })

  const lineOf = (d, name) => d.items.find((l) => l.productName === name)

  it('an order starts owing everything', () => {
    detail().then((d) => {
      expect(d.fulfilmentStatus).to.eq('NEW')
      expect(lineOf(d, nameA).quantityShipped).to.eq(0)
      expect(d.shipments || [], 'no parcels yet').to.be.an('array').and.be.empty
    })
  })

  it('you cannot MARK an order shipped — the status is derived from parcels', () => {
    cy.request({
      method: 'POST', url: '/updateOrderStatus', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false, body: { id: order.id, status: 'SHIPPED' },
    }).then((r) => {
      expect(r.body.success, 'a header that can be set independently of its parcels can lie about them').to.eq(false)
      expect(String(r.body.message || '').toLowerCase()).to.contain('shipment')
    })
    detail().then((d) => expect(d.fulfilmentStatus, 'unchanged').to.eq('NEW'))
  })

  it('shipping part of one line makes the order PARTIALLY_SHIPPED', () => {
    ship([{ orderItemId: lineA, quantity: 2 }], { carrier: 'DHL', trackingNumber: 'TRK' + run }).then((r) => {
      expect(r.body.success, `ship failed: ${JSON.stringify(r.body)}`).to.eq(true)
      expect(r.body.data.shipmentNo, 'its own SHP- series').to.match(/^SHP-\d{6}$/)
    })
    detail().then((d) => {
      expect(d.fulfilmentStatus).to.eq('PARTIALLY_SHIPPED')
      expect(lineOf(d, nameA).quantityShipped).to.eq(2)
      expect(lineOf(d, nameB).quantityShipped, 'the untouched line is untouched').to.eq(0)
      expect(d.shipments).to.have.length(1)
      expect(d.shipments[0].carrier).to.eq('DHL')
      expect(d.shipments[0].trackingNumber).to.eq('TRK' + run)
    })
  })

  it('a part-shipped order cannot be cancelled — goods on a van are not back on the shelf', () => {
    detail().then((d) => {
      // Cancelling triggers the O1 void, which returns stock. Same reasoning O2 used for SHIPPED.
      expect(d.allowedTransitions, 'no Cancel is even offered').to.not.include('CANCELLED')
    })
    cy.request({
      method: 'POST', url: '/updateOrderStatus', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false, body: { id: order.id, status: 'CANCELLED' },
    }).then((r) => expect(r.body.success, 'and the server refuses it too').to.eq(false))
  })

  it('shipping more than is outstanding is refused, and says how many are left', () => {
    ship([{ orderItemId: lineA, quantity: 99 }]).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(String(r.body.message || '')).to.contain('3 still to go')   // 5 ordered, 2 already gone
    })
    detail().then((d) => expect(lineOf(d, nameA).quantityShipped, 'nothing applied').to.eq(2))
  })

  it('an empty parcel is refused rather than burning a shipment number', () => {
    ship([{ orderItemId: lineA, quantity: 0 }]).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(String(r.body.message || '').toLowerCase()).to.contain('nothing to ship')
    })
    detail().then((d) => expect(d.shipments, 'still just the one parcel').to.have.length(1))
  })

  it('the rest of the order ships as a second parcel, and the header becomes SHIPPED', () => {
    ship([{ orderItemId: lineA, quantity: 3 }, { orderItemId: lineB, quantity: 2 }],
      { carrier: 'FedEx', trackingNumber: 'TRK2' + run }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
    })
    detail().then((d) => {
      expect(d.fulfilmentStatus, 'everything has gone').to.eq('SHIPPED')
      expect(lineOf(d, nameA).quantityShipped).to.eq(5)
      expect(lineOf(d, nameB).quantityShipped).to.eq(2)
      expect(d.shipments, 'two parcels, each with its own number').to.have.length(2)
      expect(d.shipments[0].shipmentNo).to.not.eq(d.shipments[1].shipmentNo)
      expect(d.allowedTransitions, 'only delivery remains').to.deep.eq(['DELIVERED'])
    })
  })

  it('the shopper can see both parcels with carrier and tracking', () => {
    cy.request({
      url: `/storefront/track?ref=${encodeURIComponent(order.orderNo)}&contact=${encodeURIComponent('0300SHIP' + run)}`,
      failOnStatusCode: false,
    }).then((r) => {
      expect(r.body.success, `track failed: ${JSON.stringify(r.body)}`).to.eq(true)
      const parcels = r.body.data.parcels || []
      // Without these the customer sees only the word PARTIALLY_SHIPPED, which reads like a fault.
      expect(parcels, 'the shopper sees the dispatches').to.have.length(2)
      expect(parcels[0].ref).to.match(/^SHP-\d{6}$/)
      expect(parcels.map((p) => p.carrier)).to.include.members(['DHL', 'FedEx'])
      expect(parcels[0].itemCount).to.be.greaterThan(0)
      // The shopper's view is deliberately narrower than the back office's.
      expect(parcels[0]).to.not.have.property('note')
    })
  })

  it('a fully shipped order cannot be shipped again', () => {
    ship([{ orderItemId: lineA, quantity: 1 }]).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(String(r.body.message || '')).to.contain('only 0 still to go')
    })
  })

  it('a delivered order returns the stock that the SALE removed, not just what shipped', () => {
    // O1 decrements inventory at the SALE for the full ordered quantity, so the reversal is the whole invoice.
    // Reversing only what shipped would invent a shortfall — see §2.4 of the design.
    cy.request({
      method: 'POST', url: '/updateOrderStatus', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false, body: { id: order.id, status: 'DELIVERED' },
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    let before
    cy.request('/productStock?productId=' + productA).then((r) => { before = Number(r.body.stock) })
    cy.then(() => cy.request({
      method: 'POST', url: '/processReturn', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false, body: { id: order.id },
    })).then((r) => expect(r.body.success, `return failed: ${JSON.stringify(r.body)}`).to.eq(true))

    cy.request('/productStock?productId=' + productA).then((r) => {
      expect(Number(r.body.stock), 'all 5 come back, because all 5 were sold').to.eq(before + 5)
    })
    detail().then((d) => expect(d.fulfilmentStatus).to.eq('RETURNED'))
  })

  it('a returned order cannot be shipped', () => {
    ship([{ orderItemId: lineA, quantity: 1 }]).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(String(r.body.message || '')).to.contain('cannot be shipped')
    })
  })

  it('the back office ships through the UI and shows the parcels', () => {
    // A fresh order, since the one above is now RETURNED.
    let id2
    cy.storefrontOrder(orgId, { productId: productB, quantity: 3 }, {
      customerName: 'UIShip_' + run, customerContact: '0300UI' + run,
      shippingAddress: '9 Dispatch Way', paymentMode: 'COD',
    }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      id2 = r.body.data.id
    })

    cy.then(() => {
      cy.visit('/businessDashboard')
      cy.window().then((w) => w.showOrders())
      cy.get('#ordersBody tr', { timeout: 15000 }).should('exist')
      cy.get('#ordFilterQ').clear().type('UIShip_' + run)
      cy.get('#ordSearchBtn').click()
      cy.get('#ordersBody tr').should('have.length', 1)
      cy.get('#ordersBody tr').first().within(() => {
        // "Mark SHIPPED" is gone — the server no longer offers it, so the button cannot be drawn.
        cy.contains('button', 'SHIPPED').should('not.exist')
      })
      cy.get('#ordersBody tr').first().find('a').click()
      cy.get('#orderShipBtn').click()
      cy.get('.ship-qty').should('have.value', '3').clear().type('1')
      cy.get('#shipCarrier').type('LocalVan')
      cy.get('#shipTracking').type('UITRK' + run)
      cy.get('#shipConfirmBtn').click()
      cy.get('#orderDetailBody', { timeout: 15000 }).should('contain', 'SHP-')
      cy.get('#orderDetailBody').should('contain', 'LocalVan')
    })

    cy.then(() => cy.request('/getOrder?id=' + id2)).then((r) => {
      expect(r.body.data.fulfilmentStatus).to.eq('PARTIALLY_SHIPPED')
      expect(r.body.data.items[0].quantityShipped).to.eq(1)
    })
  })
})
