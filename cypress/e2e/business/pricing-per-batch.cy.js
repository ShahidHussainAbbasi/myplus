/**
 * PR-3b — Per batch: a purchase's sell rate belongs to ITS batch. The product's price and older batches are untouched.
 *
 * Design: microservices/docs/selling-price-per-purchase-analysis.md §10.3 (PR-3b). The SALE does not yet read the batch
 * price — that is PR-3c. This slice proves the price lands on the right batch and nowhere else.
 *
 *   X1  the setting offers Per batch
 *   X2  Per batch: the batch gets 250, the product stays 200 (its cost is still recorded)
 *   X3  stock received before has no price of its own (null = the product's price) — next to the new 250 batch
 *   X4  an edit that changes ONLY the S/U rate re-prices that batch (quantity unchanged)
 *   X5  Latest (default) is unchanged: the product moves, no batch gets a price
 *   X6  the purchase form says the batch's price, not the product's
 *
 * Tenant: owner.pharma@ — a pharmacy, the shop this is for (old stock at its old printed price). Server state: the
 * purchase mode is put back EXACTLY in after().
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/pricing-per-batch.cy.js --headed --browser chrome
 */
const KEY = 'pos.pricing.purchaseMode'
const STAMP = Date.now()
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return [] }
const asPharma = () => cy.loginAsPharmaOwner()

const entry = () => cy.request('/getBusinessConfig').then((r) => cy.wrap(list(r.body).find((e) => e.key === KEY) || null))
const setMode = (v) => cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key: KEY, value: v } })
  .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
const resetMode = () => cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY }, failOnStatusCode: false })

const product = (id) => cy.request('/getCatalogProduct?id=' + id).then((r) => cy.wrap(r.body.data))
const batches = (id) => cy.request('/productStock?productId=' + id).then((r) => cy.wrap(r.body.batches || []))

let vendorId = null
const ensureVendor = () => {
  if (vendorId) return cy.wrap(vendorId)
  const vname = 'PRB_V_' + STAMP
  return cy.ensureCompany().then((companyId) => {
    cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
      body: { name: vname, companyId, mobile: '0303' + String(STAMP).slice(-7), email: 'prb' + STAMP + '@t.com' } })
    return cy.request('/getUserVenders').then((vr) => {
      const html = String(vr.body && vr.body.object != null ? vr.body.object : vr.body)
      vendorId = (new RegExp('<option value=(\\d+)[^>]*>' + vname).exec(html) || [])[1]
      expect(vendorId, 'spec vendor').to.exist
      return cy.wrap(vendorId)
    })
  })
}
const bill = (productId, venderId, qty, cost, sell, inv, batchNo) => ({
  productId, venderId, quantity: qty, purchaseRate: cost, 'stock.bpurchaseRate': cost, 'stock.bsellRate': sell,
  'stock.batchNo': batchNo, totalAmount: qty * cost, netAmount: qty * (sell - cost), purchaseInvoiceNo: inv,
})
const purchase = (productId, qty, cost, sell, inv, batchNo) => ensureVendor().then((v) =>
  cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false, body: bill(productId, v, qty, cost, sell, inv, batchNo) })
    .then((p) => { expect(p.body.status, `addPurchase ${inv}: ${JSON.stringify(p.body).slice(0, 300)}`).to.eq('SUCCESS'); return cy.wrap(v) }))
const purchaseRow = (inv) => cy.request('/getUserPurchase').then((r) => cy.wrap(list(r.body).find((p) => p.purchaseInvoiceNo === inv)))
const seed = (price, stock) => cy.seedProduct({ name: 'PRB_' + uniq(), sellingPrice: price, ...(stock ? { stock, purchasePrice: 150 } : {}) })
  .then((p) => cy.wrap(p.productId))

describe('PR-3b — Per batch: the purchase prices its own batch', () => {
  let before0 = null

  before(() => {
    asPharma()
    entry().then((e) => { before0 = e ? { chosen: e.isDefault === false, value: e.value } : { chosen: false } })
  })
  beforeEach(() => { asPharma() })
  after(() => {
    asPharma()
    cy.then(() => (before0 && before0.chosen ? setMode(before0.value) : resetMode()))
    entry().then((e) => (before0 && before0.chosen ? expect(e.value).to.eq(before0.value) : expect(e.isDefault, 'never-chosen again').to.eq(true)))
  })

  it('X1 the purchase mode offers Per batch', () => {
    entry().then((e) => expect((e.options || []).map((o) => o.value)).to.deep.eq(['latest', 'keep', 'per_batch']))
  })

  it('X2 Per batch: the batch gets 250, the product stays 200, the cost is still recorded', () => {
    setMode('per_batch')
    const batch = 'PB-' + STAMP
    seed(200).then((id) => {
      purchase(id, 4, 210, 250, 'PRB-A-' + STAMP, batch)
      product(id).then((p) => {
        expect(Number(p.sellingPrice), 'the product price is not moved').to.eq(200)
        expect(Number(p.lastPurchaseRate), 'cost still stamped').to.eq(210)
      })
      batches(id).then((bs) => {
        const b = bs.find((x) => x.batchNo === batch)
        expect(b, 'the purchase batch is listed: ' + JSON.stringify(bs)).to.exist
        expect(Number(b.sellPrice), "the batch's own price").to.eq(250)
        expect(b.stockEntryId, 'the batch id travels with it').to.be.a('number')
      })
      purchaseRow('PRB-A-' + STAMP).then((row) => expect(row, 'purchase row').to.exist)
    })
  })

  it('X3 stock received before has no price of its own, next to the new 250 batch', () => {
    setMode('per_batch')
    seed(200, 3).then((id) => {
      purchase(id, 4, 210, 250, 'PRB-O-' + STAMP, 'PB-NEW-' + STAMP)
      batches(id).then((bs) => {
        expect(bs.length, JSON.stringify(bs)).to.eq(2)
        const prices = bs.map((b) => (b.sellPrice == null ? null : Number(b.sellPrice))).sort()
        expect(prices, 'old stock: no price (the product price applies); new: 250').to.deep.eq([250, null])
      })
    })
  })

  it('X4 an edit that changes ONLY the S/U rate re-prices that batch', () => {
    setMode('per_batch')
    const inv = 'PRB-E-' + STAMP, batch = 'PB-E-' + STAMP
    seed(200).then((id) => {
      purchase(id, 4, 210, 250, inv, batch).then((v) => {
        purchaseRow(inv).then((row) => {
          cy.request({ method: 'POST', url: '/updatePurchase', form: true, failOnStatusCode: false,
            body: { ...bill(id, v, 4, 210, 260, inv, batch), purchaseId: row.purchaseId } })
            .then((r) => expect(r.body.status, JSON.stringify(r.body).slice(0, 300)).to.eq('SUCCESS'))
        })
      })
      batches(id).then((bs) => {
        const b = bs.find((x) => x.batchNo === batch)
        expect(Number(b.sellPrice), 'the batch now sells at 260').to.eq(260)
        expect(Number(b.available), 'quantity unchanged').to.eq(4)
      })
      product(id).then((p) => expect(Number(p.sellingPrice), 'the product still 200').to.eq(200))
    })
  })

  it('X5 Latest (default) is unchanged: the product moves, no batch gets a price', () => {
    resetMode()
    const batch = 'PB-L-' + STAMP
    seed(200).then((id) => {
      purchase(id, 4, 210, 250, 'PRB-L-' + STAMP, batch)
      product(id).then((p) => expect(Number(p.sellingPrice)).to.eq(250))
      batches(id).then((bs) => expect(bs.find((x) => x.batchNo === batch).sellPrice, 'no batch price in Latest').to.eq(null))
    })
  })

  it('X6 the purchase form says the batch price, not the product price', () => {
    setMode('per_batch')
    seed(200).then((id) => {
      cy.visit('/businessDashboard')
      cy.window().its('posPurchasePriceMode').should('eq', 'per_batch')
      cy.openPurchaseSection('purchaseDiv')
      cy.get('#newPurchase').click()
      cy.get('#PurchaseModal').should('have.class', 'open')
      cy.settled('#purchaseInvoiceNo')
      cy.intercept('GET', '/productStock*').as('prefill')
      cy.get('#purchaseItemDD').select(String(id), { force: true })
      cy.wait('@prefill', { timeout: 15000 })
      cy.get('#purchasePurchaseRate').clear().type('210')
      cy.get('#purchaseSellRate').clear().type('250')
      cy.get('#purchasePriceEffect').should('have.attr', 'data-effect', 'batch')
        .and('contain', '250.00').and('contain', '200.00')
    })
  })
})
