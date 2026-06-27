/**
 * Sell↔stock saga (slice 33, U3/U4) — verifies a sale routed through the inventory reservation saga:
 *   • getStock reflects INVENTORY on-hand (U4.1) + CATALOG price (U4.2),
 *   • POST /addSell reserves→confirms inventory, so the on-hand drops by the quantity sold.
 *
 * Run with `trade.saga.enabled=true` AND after the item→product + stock migration has been run; otherwise
 * this exercises the legacy local-Stock path and the decrement assertion is skipped. Request-based (like
 * flow.cy.js) so it doesn't depend on the sell-screen DOM.
 */
describe('Sell↔stock saga — sale decrements inventory on-hand', () => {
  let itemId, itemName, sellRate, stockBefore
  const ts = Date.now()
  const custName = `SagaCust_${ts}`
  const contact = `031${ts.toString().slice(-8)}`

  before(() => {
    cy.loginAsBusiness()
    // Find a migrated item that has inventory stock. With the saga on, getStock returns inventory on-hand,
    // so we scan items until one reports stock > 0.
    cy.request('/getUserItem').then((res) => {
      // /getUserItem returns a GenericResponse — the item list is in `collection` (not `data`). Reading
      // `data` was the bug that made this spec silently skip every sale (items=[] -> never sells).
      const items = res.body.collection || res.body.object || res.body.data || []
      const tryItem = (idx) => {
        if (idx >= items.length || itemId) return
        const it = items[idx]
        cy.request({ url: `/getStock?itemId=${it.id}`, failOnStatusCode: false }).then((r) => {
          const s = r.body || {}
          if (!itemId && s.stock && s.stock > 0) {
            itemId = it.id
            itemName = it.iname
            stockBefore = s.stock
            sellRate = s.bsellRate || 1
            cy.log(`Selling "${it.iname}" (id=${it.id}); inventory on-hand=${stockBefore}, catalog price=${sellRate}`)
          } else {
            tryItem(idx + 1)
          }
        })
      }
      tryItem(0)
    })
  })

  beforeEach(() => cy.loginAsBusiness())

  it('getStock reports a positive inventory on-hand for a stocked item', () => {
    if (!itemId) return cy.log('No stocked+migrated item found — run migrate-catalog/migrate-stock and seed stock first')
    expect(stockBefore).to.be.gt(0)
  })

  it('POST /addSell (saga path) succeeds and returns an invoice', () => {
    if (!itemId) return cy.log('No stocked item — skipping')
    cy.request({
      method: 'POST', url: '/addSell',
      headers: { 'Content-Type': 'application/json' },
      body: {
        customer: { name: custName, contact, paidAmount: sellRate, dueAmount: 0 },
        sales: [{ itemId, quantity: 1, sellRate, totalAmount: sellRate, netAmount: sellRate }],
      },
      failOnStatusCode: false,
    }).then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status, JSON.stringify(res.body).substring(0, 200)).to.eq('SUCCESS')
      cy.log(`addSell: ${res.body.message}`)
    })
  })

  it('inventory on-hand (via getStock) drops after the sale', () => {
    if (!itemId) return cy.log('No stocked item — skipping')
    // The saga reserves synchronously but the confirm/decrement can settle a beat later (recovery relay),
    // so poll getStock briefly until on-hand has dropped rather than asserting an exact value immediately.
    const poll = (tries) => {
      cy.request({ url: `/getStock?itemId=${itemId}`, failOnStatusCode: false }).then((r) => {
        const after = (r.body || {}).stock
        if (after <= stockBefore - 1 || tries <= 0) {
          cy.log(`on-hand before=${stockBefore}, after=${after}`)
          expect(after, 'saga decremented inventory on-hand').to.be.lte(stockBefore - 1)
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
