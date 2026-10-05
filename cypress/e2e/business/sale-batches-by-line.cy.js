/**
 * PR-3a — one product on two lines of a sale: stock is checked for BOTH together, and each line records ITS OWN batches.
 *
 * Design: microservices/docs/selling-price-per-purchase-analysis.md §10.2
 *
 * Found by the PR-3 trace, in the live data: invoice 6292 sold 2 units of one product on two lines, and its batch records
 * said 4 — each line recorded both lines' batches, matched by product. Returns and edits cost from those records. And the
 * stock check looked at each line alone, so two lines of 5 against 7 on the shelf both "fitted" and the sale went
 * through holding 7.
 *
 *   B1  2 + 3 of one product: each line's batches sum to its own quantity (5 in all, not 10)
 *   B2  5 + 5 against 7 on the shelf: refused, nothing held, nothing sold
 *   B3  an ordinary one-line sale still records its batch (regression)
 *
 * Tenant: owner.business@. Each case seeds its own product and stock.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/sale-batches-by-line.cy.js --headed --browser chrome
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return [] }

let customer = null
const ensureCustomer = () => {
  if (customer) return cy.wrap(customer)
  const name = 'PR3A_' + uniq()
  return cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name, contact: '03' + uniq().slice(-9), customerType: 'WALK_IN' } })
    .then(() => cy.request('/getUserCustomer?q=-1'))
    .then((r) => { customer = list(r.body).find((c) => c.name === name); expect(customer, 'spec customer').to.exist; return cy.wrap(customer) })
}

const sell = (lines) => ensureCustomer().then((c) =>
  cy.request({ method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      customer: { customerId: c.customerId, name: c.name, contact: c.contact, customerType: c.customerType },
      sales: lines, tenders: [], paidAmount: 0, idempotencyKey: 'cy-pr3a-' + uniq(),
    } }))

const receipt = (invoiceNo) => cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo)).then((r) => cy.wrap(r.body.object || r.body.data))
const sellable = (productId) => cy.request('/productStock?productId=' + productId).then((r) => cy.wrap(Number(r.body.stock)))
const batchQty = (line) => (line.batches || []).reduce((n, b) => n + Number(b.quantity || 0), 0)

describe('PR-3a — one product on two lines', () => {
  beforeEach(() => { cy.loginAsOwner() })

  it('B1 2 + 3 of one product: each line records only its own batches (5 in all, not 10)', () => {
    cy.seedProduct({ name: 'PR3A_' + uniq(), sellingPrice: 100, stock: 10 }).then(({ productId }) => {
      sell([{ productId, quantity: 2 }, { productId, quantity: 3 }]).then((s) => {
        expect(s.body.status, JSON.stringify(s.body).slice(0, 300)).to.eq('SUCCESS')
        receipt(s.body.object).then((inv) => {
          const mine = (inv.sales || []).filter((l) => Number(l.productId) === productId)
          expect(mine.length, 'two lines').to.eq(2)
          const byQty = mine.map((l) => [Number(l.quantity), batchQty(l)]).sort((a, b) => a[0] - b[0])
          expect(byQty, 'each line records exactly its own units').to.deep.eq([[2, 2], [3, 3]])
        })
      })
      sellable(productId).then((n) => expect(n, '10 − 5 left on the shelf').to.eq(5))
    })
  })

  it('B2 5 + 5 against 7 on the shelf: refused, nothing held, nothing sold', () => {
    cy.seedProduct({ name: 'PR3A_' + uniq(), sellingPrice: 100, stock: 7 }).then(({ productId }) => {
      sell([{ productId, quantity: 5 }, { productId, quantity: 5 }]).then((s) => {
        expect(s.body.status, 'refused: ' + JSON.stringify(s.body).slice(0, 300)).to.not.eq('SUCCESS')
        expect(JSON.stringify(s.body)).to.match(/7 sellable, 10 requested/)
      })
      sellable(productId).then((n) => expect(n, 'all 7 still sellable — nothing held').to.eq(7))
      // and the shelf still sells what it has
      sell([{ productId, quantity: 7 }]).then((s) => expect(s.body.status, JSON.stringify(s.body).slice(0, 200)).to.eq('SUCCESS'))
    })
  })

  it('B3 an ordinary one-line sale still records its batch', () => {
    cy.seedProduct({ name: 'PR3A_' + uniq(), sellingPrice: 100, stock: 4 }).then(({ productId }) => {
      sell([{ productId, quantity: 3 }]).then((s) => {
        expect(s.body.status).to.eq('SUCCESS')
        receipt(s.body.object).then((inv) => {
          const l = (inv.sales || []).find((x) => Number(x.productId) === productId)
          expect(batchQty(l), 'its 3 units are recorded').to.eq(3)
        })
      })
    })
  })
})
