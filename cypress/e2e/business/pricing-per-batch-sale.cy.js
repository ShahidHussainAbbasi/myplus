/**
 * PR-3c — Per batch: the SALE is priced from the batches it takes, split where they differ, and holds exactly them.
 *
 * Design: microservices/docs/selling-price-per-purchase-analysis.md §10.3–10.4, §10.7.
 *
 * The analysis's example, on a pharmacy: abc-123 bought 7 with a sell rate of 200 (batch OLD), later 10 at 250 (batch
 * NEW). The product's own price is 240 and is never moved in Per batch.
 *
 *   S1  a sale of 10 is invoiced 7 @ 200 + 3 @ 250, each line recording its own batch; the shelf drops by 10
 *   S2  the cashier's own price wins: typed 230 → one line @ 230 (still 10 units, both batches taken)
 *   S3  the cashier chooses the NEW batch: 3 @ 250 from NEW, although FEFO would have taken OLD
 *   S4  the preview says 7 @ 200 + 3 @ 250 and holds nothing
 *   S5  Latest (any other mode) is unchanged: one line at the product's price
 *   S6  the till: Batch choice shows both batches with their prices; the rate box starts at OLD's 200; adding 10
 *       puts two lines in the cart (7 × 200, 3 × 250); Complete Sale records exactly that
 *   S7  the till: 7 then 3 added separately — Complete re-checks the whole cart, shows the 3 @ 250 and posts nothing;
 *       Complete again records it
 *
 * Tenant: owner.pharma@. Server state: the purchase mode is put back EXACTLY in after().
 *
 * Run:  npx cypress run --spec cypress/e2e/business/pricing-per-batch-sale.cy.js
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

let vendorId = null
const ensureVendor = () => {
  if (vendorId) return cy.wrap(vendorId)
  const vname = 'PRS_V_' + STAMP
  return cy.ensureCompany().then((companyId) => {
    cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
      body: { name: vname, companyId, mobile: '0304' + String(STAMP).slice(-7), email: 'prs' + STAMP + '@t.com' } })
    return cy.request('/getUserVenders').then((vr) => {
      const html = String(vr.body && vr.body.object != null ? vr.body.object : vr.body)
      vendorId = (new RegExp('<option value=(\\d+)[^>]*>' + vname).exec(html) || [])[1]
      expect(vendorId, 'spec vendor').to.exist
      return cy.wrap(vendorId)
    })
  })
}
const purchase = (productId, qty, cost, sell, inv, batchNo) => ensureVendor().then((v) =>
  cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false, body: {
    productId, venderId: v, quantity: qty, purchaseRate: cost, 'stock.bpurchaseRate': cost, 'stock.bsellRate': sell,
    'stock.batchNo': batchNo, totalAmount: qty * cost, netAmount: qty * (sell - cost), purchaseInvoiceNo: inv } })
    .then((p) => expect(p.body.status, `addPurchase ${inv}: ${JSON.stringify(p.body).slice(0, 300)}`).to.eq('SUCCESS')))

let customer = null
const ensureCustomer = () => {
  if (customer) return cy.wrap(customer)
  const name = 'PRS_C_' + uniq()
  return cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name, contact: '03' + uniq().slice(-9), customerType: 'WALK_IN' } })
    .then(() => cy.request('/getUserCustomer?q=-1'))
    .then((r) => { customer = list(r.body).find((c) => c.name === name); expect(customer, 'spec customer').to.exist; return cy.wrap(customer) })
}
const custBody = (c) => ({ customerId: c.customerId, name: c.name, contact: c.contact, customerType: c.customerType })
const sell = (lines) => ensureCustomer().then((c) =>
  cy.request({ method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: { customer: custBody(c), sales: lines, tenders: [], paidAmount: 0, idempotencyKey: 'cy-pr3c-' + uniq() } }))
const preview = (lines) => ensureCustomer().then((c) =>
  cy.request({ method: 'POST', url: '/batchPricePreview', headers: { 'Content-Type': 'application/json' },
    body: { customer: custBody(c), sales: lines } }))
const receipt = (invoiceNo) => cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo)).then((r) => cy.wrap(r.body.object || r.body.data))
const batches = (id) => cy.request('/productStock?productId=' + id).then((r) => cy.wrap(r.body.batches || []))
const sellable = (id) => cy.request('/productStock?productId=' + id).then((r) => cy.wrap(Number(r.body.stock)))
const batchQty = (line) => (line.batches || []).reduce((n, b) => n + Number(b.quantity || 0), 0)
const linesOf = (inv, id) => (inv.sales || []).filter((l) => Number(l.productId) === Number(id))

/** abc-123: product price 240; OLD = 7 sold at 200; NEW = 10 sold at 250 (Per batch purchases). */
const seedTwoBatches = (tag) => {
  const name = 'PRS_' + tag + '_' + uniq()
  return cy.seedProduct({ name, sellingPrice: 240 }).then(({ productId }) => {
    purchase(productId, 7, 150, 200, `PRS-${tag}-O-${STAMP}`, `OLD-${tag}-${STAMP}`)
    purchase(productId, 10, 210, 250, `PRS-${tag}-N-${STAMP}`, `NEW-${tag}-${STAMP}`)
    return batches(productId).then((bs) => {
      const old = bs.find((b) => b.batchNo === `OLD-${tag}-${STAMP}`), neu = bs.find((b) => b.batchNo === `NEW-${tag}-${STAMP}`)
      expect(old && Number(old.sellPrice), 'OLD sells at 200: ' + JSON.stringify(bs)).to.eq(200)
      expect(neu && Number(neu.sellPrice), 'NEW sells at 250').to.eq(250)
      return cy.wrap({ productId, name, old, neu })
    })
  })
}
const tillLine = (productId, qty, rate, extra) => ({ productId, quantity: qty, sellRate: rate, autoRate: rate, ...(extra || {}) })

describe('PR-3c — Per batch: the sale is priced from its batches', () => {
  let before0 = null

  before(() => {
    asPharma()
    entry().then((e) => { before0 = e ? { chosen: e.isDefault === false, value: e.value } : { chosen: false } })
  })
  beforeEach(() => { asPharma(); setMode('per_batch') })
  after(() => {
    asPharma()
    cy.then(() => (before0 && before0.chosen ? setMode(before0.value) : resetMode()))
    entry().then((e) => (before0 && before0.chosen ? expect(e.value).to.eq(before0.value) : expect(e.isDefault, 'never-chosen again').to.eq(true)))
  })

  it('S1 10 units across two batches are invoiced 7 @ 200 + 3 @ 250, each line with its own batch', () => {
    seedTwoBatches('S1').then(({ productId, old, neu }) => {
      sell([tillLine(productId, 10, 200)]).then((s) => {
        expect(s.body.status, JSON.stringify(s.body).slice(0, 400)).to.eq('SUCCESS')
        receipt(s.body.object).then((inv) => {
          const ls = linesOf(inv, productId).map((l) => ({ q: Number(l.quantity), r: Number(l.sellRate), b: batchQty(l),
            n: (l.batches || []).map((x) => x.batchNo).join() }))
          expect(ls, JSON.stringify(ls)).to.deep.eq([
            { q: 7, r: 200, b: 7, n: old.batchNo },
            { q: 3, r: 250, b: 3, n: neu.batchNo }])
        })
      })
      sellable(productId).then((n) => expect(n, '17 − 10').to.eq(7))
      batches(productId).then((bs) => {
        expect(bs.find((b) => b.batchNo === old.batchNo), 'OLD sold out').to.be.undefined
        expect(Number(bs.find((b) => b.batchNo === neu.batchNo).available), 'NEW has 7 left').to.eq(7)
      })
    })
  })

  it('S2 the cashier\'s own price wins: typed 230 → one line @ 230, both batches still taken', () => {
    seedTwoBatches('S2').then(({ productId }) => {
      sell([{ productId, quantity: 10, sellRate: 230, autoRate: 200 }]).then((s) => {
        expect(s.body.status, JSON.stringify(s.body).slice(0, 400)).to.eq('SUCCESS')
        receipt(s.body.object).then((inv) => {
          const ls = linesOf(inv, productId)
          expect(ls.length).to.eq(1)
          expect(Number(ls[0].sellRate)).to.eq(230)
          expect(batchQty(ls[0])).to.eq(10)
        })
      })
    })
  })

  it('S3 the cashier chooses the NEW batch: 3 @ 250 from NEW, though FEFO would take OLD', () => {
    seedTwoBatches('S3').then(({ productId, old, neu }) => {
      sell([tillLine(productId, 3, 250, { stockEntryId: neu.stockEntryId })]).then((s) => {
        expect(s.body.status, JSON.stringify(s.body).slice(0, 400)).to.eq('SUCCESS')
        receipt(s.body.object).then((inv) => {
          const ls = linesOf(inv, productId)
          expect(ls.length).to.eq(1)
          expect(Number(ls[0].sellRate)).to.eq(250)
          expect((ls[0].batches || []).map((b) => b.batchNo)).to.deep.eq([neu.batchNo])
        })
      })
      batches(productId).then((bs) => expect(Number(bs.find((b) => b.batchNo === old.batchNo).available), 'OLD untouched').to.eq(7))
    })
  })

  it('S4 the preview answers 7 @ 200 + 3 @ 250 and holds nothing', () => {
    seedTwoBatches('S4').then(({ productId, old, neu }) => {
      preview([tillLine(productId, 10, 200)]).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        expect(r.body.perBatch).to.eq(true)
        expect(r.body.parts.map((p) => [Number(p.quantity), Number(p.rate), p.stockEntryId]))
          .to.deep.eq([[7, 200, old.stockEntryId], [3, 250, neu.stockEntryId]])
      })
      sellable(productId).then((n) => expect(n, 'nothing held').to.eq(17))
    })
  })

  it('S5 Latest is unchanged: one line at the product price', () => {
    seedTwoBatches('S5').then(({ productId }) => {
      setMode('latest')
      sell([tillLine(productId, 10, 240)]).then((s) => {
        expect(s.body.status, JSON.stringify(s.body).slice(0, 400)).to.eq('SUCCESS')
        receipt(s.body.object).then((inv) => {
          const ls = linesOf(inv, productId)
          expect(ls.length).to.eq(1)
          expect(Number(ls[0].sellRate)).to.eq(240)
        })
      })
      preview([tillLine(productId, 1, 240)]).then((r) => expect(r.body.perBatch, 'no preview outside Per batch').to.eq(false))
    })
  })

  const openSale = () => {
    cy.visitSaleScreen()
    cy.window().its('posPurchasePriceMode').should('eq', 'per_batch')
    cy.get('#sellItemDD option', { timeout: 30000 }).should('have.length.greaterThan', 1)
  }
  const pickItem = (productId) => {
    cy.intercept('GET', '/productStock*').as('stock')
    cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 15000 }).should('exist')
    cy.get('#sellItemDD').select(String(productId), { force: true })
    cy.wait('@stock', { timeout: 15000 })
    cy.get('#sellBatchPickRow').should('be.visible')
  }
  const addToCart = (qty) => {
    cy.get('#sellQuantity', { timeout: 20000 }).should('not.be.disabled').clear().type(String(qty))
    cy.intercept('POST', '/batchPricePreview').as('pv')
    cy.get('#addInviceItem').click()
    cy.wait('@pv')
  }
  /** Complete Sale. In Per batch the till re-checks the cart FIRST, so the confirm dialog opens only after it answers. */
  const complete = ({ expectConfirm = true } = {}) => {
    cy.get('#sellPayMethod').select('CASH', { force: true })
    cy.get('#sellRec').clear().type('99999')
    cy.intercept('POST', '/batchPricePreview').as('check')
    cy.get('#addSell').click({ timeout: 30000 })
    cy.wait('@check')
    if (expectConfirm) cy.confirmSale({ optional: true })
  }

  it('S6 the till: the Batch choice, then 10 added → 7 × 200 and 3 × 250 in the cart, and that is what is recorded', () => {
    seedTwoBatches('S6').then(({ productId, name, old, neu }) => {
      openSale()
      pickItem(productId)
      cy.get('#sellBatchPickRow').should('be.visible')
      cy.get('#sellBatchPick option').should('have.length', 3)
      cy.get('#sellBatchPick option').eq(1).should('contain', old.batchNo).and('contain', '7 @ 200.00')
      cy.get('#sellBatchPick option').eq(2).should('contain', neu.batchNo).and('contain', '10 @ 250.00')
      cy.get('#sellSellRate').should('have.value', '200')
      addToCart(10)
      cy.get('#tablesi tbody tr').should('have.length', 2)
      cy.get('#tablesi tbody tr').eq(0).should('contain', '7').and('contain', '200').and('contain', old.batchNo)
      cy.get('#tablesi tbody tr').eq(1).should('contain', '3').and('contain', '250').and('contain', neu.batchNo)
      cy.get('#sellBatchNote').should('be.visible').and('contain', '7 @ 200.00').and('contain', '3 @ 250.00')
      cy.get('#sellTotal').should('contain', '2150')
      cy.intercept('POST', '/addSell').as('sale')
      complete()
      cy.wait('@sale').its('response.body').then((b) => {
        expect(b.status, JSON.stringify(b).slice(0, 300)).to.eq('SUCCESS')
        receipt(b.object).then((inv) => {
          expect(linesOf(inv, productId).map((l) => [Number(l.quantity), Number(l.sellRate)])).to.deep.eq([[7, 200], [3, 250]])
        })
      })
      cy.wrap(name).should('be.a', 'string')
    })
  })

  it('S7 the till: 7 then 3 added apart — Complete re-checks the cart, shows 3 @ 250 and posts nothing until Complete again', () => {
    seedTwoBatches('S7').then(({ productId, neu }) => {
      openSale()
      pickItem(productId); addToCart(7)
      pickItem(productId); addToCart(3)
      // Each add was priced alone, so the second line still says 200 — the shelf has not been held yet.
      cy.get('#tablesi tbody tr').should('have.length', 2)
      cy.get('#tablesi tbody tr').eq(1).should('contain', '200')
      let posted = 0
      cy.intercept('POST', '/addSell', () => { posted++ }).as('sale')
      complete({ expectConfirm: false })   // the re-check changes the cart, so no dialog: nothing is posted
      cy.get('#sellBatchNote').should('be.visible').and('contain', 'changed its prices')
      cy.get('#tablesi tbody tr').eq(1).should('contain', '250').and('contain', neu.batchNo)
      cy.then(() => expect(posted, 'nothing posted on the first Complete').to.eq(0))
      complete()
      cy.wait('@sale').its('response.body').then((b) => {
        expect(b.status, JSON.stringify(b).slice(0, 300)).to.eq('SUCCESS')
        receipt(b.object).then((inv) => {
          expect(linesOf(inv, productId).map((l) => [Number(l.quantity), Number(l.sellRate)])).to.deep.eq([[7, 200], [3, 250]])
        })
      })
    })
  })
})
