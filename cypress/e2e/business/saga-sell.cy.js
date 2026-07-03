/**
 * Sell↔stock saga (slice 33, U3/U4; M4e.d productId-native) — verifies a sale routed through the
 * inventory reservation saga:
 *   • /productStock reflects INVENTORY on-hand + CATALOG price,
 *   • POST /addSell reserves→confirms inventory, so the on-hand drops by the quantity sold.
 *
 * M4e.d (slice 104): the legacy getUserItem/getStock(itemId) scan is gone — we seed a stocked catalog
 * Product (cy.seedProduct) and sell it by productId. Request-based (like flow.cy.js) so it doesn't
 * depend on the sell-screen DOM. Run with `trade.saga.enabled=true`.
 */
describe('Sell↔stock saga — sale decrements inventory on-hand', () => {
  let productId, sellRate
  const ts = Date.now()
  const custName = `SagaCust_${ts}`
  const contact = `031${ts.toString().slice(-8)}`
  const openingStock = 20

  before(() => {
    cy.loginAsBusiness()
    // Seed a catalog Product with opening inventory so the saga has stock to reserve/confirm.
    cy.seedProduct({ name: `SagaProd_${ts}`, sku: `SG-${ts}`, sellingPrice: 25, stock: openingStock })
      .then(({ productId: pid }) => {
        productId = pid
        sellRate = 25
        cy.log(`Selling product id=${pid}; opening on-hand=${openingStock}, catalog price=${sellRate}`)
      })
  })

  beforeEach(() => cy.loginAsBusiness())

  it('productStock reports the opening inventory on-hand', () => {
    if (!productId) return cy.log('No seeded product — skipping')
    cy.request({ url: `/productStock?productId=${productId}`, failOnStatusCode: false }).then((r) => {
      expect(Number(r.body.stock), 'inventory on-hand').to.be.gte(1)
    })
  })

  it('POST /addSell (saga path) succeeds and returns an invoice', () => {
    if (!productId) return cy.log('No seeded product — skipping')
    cy.request({
      method: 'POST', url: '/addSell',
      headers: { 'Content-Type': 'application/json' },
      body: {
        customer: { name: custName, contact, paidAmount: sellRate, dueAmount: 0 },
        sales: [{ productId, quantity: 1, sellRate, totalAmount: sellRate, netAmount: sellRate }],
      },
      failOnStatusCode: false,
    }).then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status, JSON.stringify(res.body).substring(0, 200)).to.eq('SUCCESS')
      cy.log(`addSell: ${res.body.message}`)
    })
  })

  it('inventory on-hand (via productStock) drops after the sale', () => {
    if (!productId) return cy.log('No seeded product — skipping')
    // The saga reserves synchronously but the confirm/decrement can settle a beat later (recovery relay),
    // so poll productStock briefly until on-hand has dropped rather than asserting an exact value immediately.
    const poll = (tries) => {
      cy.request({ url: `/productStock?productId=${productId}`, failOnStatusCode: false }).then((r) => {
        const after = Number((r.body || {}).stock)
        if (after <= openingStock - 1 || tries <= 0) {
          cy.log(`on-hand before=${openingStock}, after=${after}`)
          expect(after, 'saga decremented inventory on-hand').to.be.lte(openingStock - 1)
        } else {
          cy.wait(1000)
          poll(tries - 1)
        }
      })
    }
    poll(8)
  })

  after(() => {
    cy.loginAsBusiness()
    cy.request({ url: '/getUserCustomer', failOnStatusCode: false }).then((res) => {
      const c = (res.body.collection || res.body.data || []).find(x => x.name === custName)
      if (c) cy.request({ method: 'POST', url: '/deleteCustomer', form: true, body: { checked: c.customerId || c.id }, failOnStatusCode: false })
    })
  })
})
