/**
 * Price override: the rate the cashier actually sold at must be what gets recorded.
 *
 * The bug: the browser sent sellRate, but the MONOLITH's SellDTO had no such field, so it was dropped on the
 * relay. business-service then fell back to the catalog price, while the client's totalAmount (the real, lower
 * figure) was trusted verbatim. A product with a 1000 catalog price sold at 850 was stored as:
 *
 *     sell_rate = 1000, quantity = 1, discount = 0, total_amount = 850     <-- rate x qty != total
 *
 * ...and the margin report, which computes netAmount - cost x qty, then reported a 200 profit on a sale whose
 * real margin was 50.
 *
 * Two things are asserted here, and the second is the one that makes the class of bug impossible:
 *   1. the sold rate survives the relay;
 *   2. the server DERIVES the line total from qty x soldRate and ignores a contradictory client total.
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const rows = (body) => {
  for (const key of ['collection', 'object', 'data']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return []
}

describe('Sale: the cashier\'s price is what gets recorded', () => {
  const CATALOG = 1000
  const SOLD = 850
  let productId
  const pname = `CY_Override_${uniq()}`

  // testIsolation clears the session BETWEEN tests, so a login in before() only covers the first one — the
  // rest hit the "Session Timed Out" page and get HTML where they expect JSON. Every authed cy.request needs
  // a live session, hence beforeEach.
  beforeEach(() => cy.loginAsOwner())

  before(() => {
    cy.loginAsOwner()
    cy.seedProduct({ name: pname, sellingPrice: CATALOG, taxRate: 0, stock: 10 })
      .then((p) => { productId = p.productId })
  })

  it('records the sold rate, not the catalog price', () => {
    const cust = `CY_OV_${uniq()}`
    cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      body: {
        customer: { name: cust, contact: '0300OV', paidAmount: 500, dueAmount: 0 },
        // The cashier knocked the price down from 1000 to 850.
        sales: [{ productId, quantity: 1, sellRate: SOLD, totalAmount: SOLD, netAmount: SOLD }],
        paidAmount: 500, dueAmount: 0, grandTotal: SOLD,
        tenders: [{ method: 'CASH', amount: 500, reference: '' }],
      },
      failOnStatusCode: false,
    }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

    cy.request('/getAllSell').then((r) => {
      const line = rows(r.body).filter((s) => Number(s.productId) === Number(productId)).pop()
      expect(line, 'the sale line exists').to.exist

      expect(Number(line.sellRate), 'sold rate survived the relay (was falling back to the catalog price)')
        .to.eq(SOLD)
      expect(Number(line.catalogPrice), 'the catalog price is still snapshotted separately').to.eq(CATALOG)
      // The invariant that was violated: rate x qty - discount == total.
      expect(Number(line.totalAmount), 'total agrees with the rate it claims to have sold at').to.eq(SOLD)
      expect(Number(line.netAmount), 'line total = what was charged (no discount, no tax here)').to.eq(SOLD)
    })
  })

  it('ignores a client-sent line total that contradicts the rate', () => {
    const cust = `CY_LIE_${uniq()}`
    cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      body: {
        customer: { name: cust, contact: '0300LIE', paidAmount: 0, dueAmount: 0 },
        // A deliberately inconsistent payload: rate 850 x 2 = 1700, but the client claims 9999.
        sales: [{ productId, quantity: 2, sellRate: SOLD, totalAmount: 9999, netAmount: 9999 }],
        paidAmount: 0, dueAmount: 0, grandTotal: 9999,
      },
      failOnStatusCode: false,
    }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

    cy.request('/getAllSell').then((r) => {
      const line = rows(r.body)
        .filter((s) => Number(s.productId) === Number(productId) && Number(s.quantity) === 2).pop()
      expect(line, 'the 2-qty line exists').to.exist
      // Money is server-derived: the client's 9999 must not appear anywhere.
      expect(Number(line.totalAmount), 'total derived as qty x soldRate, not taken from the client').to.eq(SOLD * 2)
      expect(Number(line.netAmount), 'line total derived too').to.eq(SOLD * 2)
    })
  })
})
