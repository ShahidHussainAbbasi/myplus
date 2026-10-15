/**
 * NUM-1 — quantities are exact decimals, never floats (standard D14). Design: microservices/docs/slices/num-1-float-to-decimal.md
 *
 * WRITTEN BEFORE THE CHANGE. On the build it was written against:
 *   RED   N1, N2a — stock is kept at 2 decimals (inventory drift) after a float hop (business Float quantity), so a
 *                    loose sale lands as 4.93 / 4.67 instead of 4.925 / 4.6667
 *         N3      — a whole-box quantity exports as "2.0" (String.valueOf(Float)); after NUM-1 it must print "2"
 *                    (and must NOT print "2.0000", which a bare BigDecimal would)
 *   GREEN N2b     — returning the tablet puts the stock back to exactly 5 (it happens to round back today)
 *
 * TENANT demo.business@ — its ORG-WIDE tax and loose markup are pinned (off / 0) for the run and restored after.
 * Each case makes its own products; nothing shared is assumed.
 *
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/num-1-decimal-quantities.cy.js
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const PRICE = 311.60
const COST = 100

/** A splittable box of `pack` pieces, exempt from expiry (a fixture). */
const packProduct = (name, pack) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: { name, sku: `NUM1${uniq()}`, sellingPrice: PRICE, taxRate: 0, unit: 'box', packSize: pack,
      looseUnit: 'tablet', looseUnitPlural: 'tablets', allowLoose: true, defaultSellUnit: 'PACK' },
  }).then((r) => {
    expect(r.body && r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)
    return cy.markNoExpiry(r.body.data.id).then(() => r.body.data.id)
  })

const stockIn = (productId, boxes) =>
  cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: { productId, quantity: boxes, 'stock.batchNo': `NUM1B${uniq()}`, 'stock.bpurchaseRate': COST,
      'stock.bsellRate': PRICE, totalAmount: boxes * COST, netAmount: boxes * COST, purchaseInvoiceNo: `NUM1-${uniq()}` } })
    .then((r) => expect(r.body.status, `stock in: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS'))

const sell = (sales, total) =>
  cy.request({ method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: { customer: { name: `NUM1_${uniq()}`, contact: '03009999999' }, sales,
      tenders: [{ method: 'CASH', amount: total }], paidAmount: total, dueAmount: 0, grandTotal: total,
      idempotencyKey: `cy-num1-${uniq()}` } })
    .then((r) => expect(r.body.status, `the sale: ${JSON.stringify(r.body).slice(0, 220)}`).to.eq('SUCCESS'))

/** On-hand, in BOXES, as the screen reads it. */
const onHand = (productId) =>
  cy.request(`/productStock?productId=${productId}`).then((r) => Number((r.body && r.body.stock) || 0))

/** This product's sale line (a flat list: each row is a line), or null. */
const lineOf = (productId) =>
  cy.request('/getUserSell?q=-1').then((r) => {
    const rows = (r.body && (r.body.collection || r.body.data)) || []
    return rows.filter((x) => String(x.productId) === String(productId))[0] || null
  })

describe('NUM-1 — quantities are exact decimals (D14)', () => {
  let taxBefore = null
  let markupBefore = null

  before(() => {
    cy.loginAsBusiness()
    cy.snapshotTaxSetting().then((s) => { taxBefore = s; cy.setTaxSetting({ ...s, enabled: false }) })
    cy.snapshotBusinessConfig('pos.sale.looseMarkupPct').then((v) => { markupBefore = v; cy.setBusinessConfig('pos.sale.looseMarkupPct', 0) })
  })

  after(() => {
    cy.loginAsBusiness()
    if (markupBefore != null) cy.setBusinessConfig('pos.sale.looseMarkupPct', markupBefore)
    cy.restoreTaxSetting(taxBefore)
  })

  beforeEach(() => cy.loginAsBusiness())

  it('⭐⭐ N1 — 3 tablets of a 40-box leave exactly 4.925 boxes, not 4.93', () => {
    packProduct(`NUM1 forty ${uniq()}`, 40).then((productId) => {
      stockIn(productId, 5)
      onHand(productId).then((s) => expect(s, 'five boxes in').to.eq(5))
      sell([{ productId, soldUnit: 'LOOSE', soldQuantity: 3 }], 23.37)   // 3 × 7.79
      onHand(productId).then((s) => expect(s, 'stock after 3 of 40 tablets — 0.075 of a box, kept exactly').to.eq(4.925))
    })
  })

  it('⭐⭐ N2 — 1 tablet of a 3-box leaves 4.6667 boxes; returning it restores exactly 5', () => {
    packProduct(`NUM1 three ${uniq()}`, 3).then((productId) => {
      stockIn(productId, 5)
      sell([{ productId, soldUnit: 'LOOSE', soldQuantity: 1 }], 103.87)  // 311.60 / 3, a piece price rounds UP
      onHand(productId).then((s) => expect(s, 'N2a: a third of a box left the shelf, to 4 places').to.eq(4.6667))
      lineOf(productId).then((line) => {
        expect(line, 'the sale line is readable').to.not.eq(null)
        cy.request({ method: 'POST', url: '/saleReturn', form: true, failOnStatusCode: false,
          body: { sellId: line.sellId || line.id, quantity: 1, returnUnit: 'LOOSE', reason: 'num1', quarantine: false, refundAs: 'CASH' } })
          .its('body.status').should('eq', 'SUCCESS')
      })
      onHand(productId).then((s) => expect(s, 'N2b: the tablet back — exactly five boxes, nothing left behind').to.eq(5))
    })
  })

  it('⭐ N3 — a whole-box quantity exports as "2" — not "2.0", not "2.0000"', () => {
    const name = `NUM1 csv ${uniq()}`
    packProduct(name, 40).then((productId) => {
      stockIn(productId, 5)
      sell([{ productId, quantity: 2, sellRate: PRICE, totalAmount: PRICE * 2, netAmount: PRICE * 2 }], PRICE * 2)
      cy.request('/saleReport.csv?rp=0').then((r) => {
        const row = String(r.body).split(/\r?\n/).find((l) => l.includes(name))
        expect(row, `the CSV lists ${name}`).to.be.a('string')
        // columns: Date, Invoice, Customer, Channel, SKU, Item, Category, Qty, Rate, Total, Tax
        const qty = row.split(',')[7]
        expect(qty, 'the Qty column').to.eq('2')
      })
    })
  })
})
