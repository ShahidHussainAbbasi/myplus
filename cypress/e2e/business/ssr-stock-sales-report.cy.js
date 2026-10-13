/**
 * SSR — the Stock & Sales Report, end to end (design: microservices/docs/slices/110-stock-ledger-ssr.md).
 *
 * One scripted month on a NEW product in owner.business@, then the exact row the report must give:
 *
 *   step                                   ledger           on hand
 *   S1 purchase 100 @ 50 from SSR supplier  PURCHASE +100     100
 *   S2 sell 30 @ 70                         SALE −30           70
 *   S3 customer returns 5                   SALE_RETURN +5     75
 *   S4 10 sent back to the supplier         PURCHASE_RETURN −10 65
 *   S5 correction −2 (damaged)              ADJUSTMENT −2      63
 *
 *   → Opening 0 · Purchase 100 · Purch. return 10 · Total 90 · Sales 30 / 2,100 · Returns 5 / 350 ·
 *     Net 25 / 1,750 · Bonus 0 · Adjust −2 · Closing 63 / 3,150 (at TP 50) — and the row adds up.
 *
 * What each case protects:
 *   R1  the bill's supplier became the product's MAIN SUPPLIER (SSR-0c) — and the product form shows it
 *   R2  the report's row, figure by figure, grouped under that supplier, with the subtotal and Net Total
 *   R3  the screen: Finance → Stock & Sales, period + supplier, Show → the same row on screen
 *   R4  PDF and CSV are built from what is on screen
 *   R5  a bad period is refused with a reason; R6 a plain user cannot read the report
 *
 * Cleanup: the spec makes its own supplier and product (SSR_…); their bills, sale and correction stay in
 * owner.business's history like every other spec's — no server-wide setting is changed.
 *
 * Capture the step-by-step guide:  --env capture=1   (screenshots to cypress/screenshots/ssr-stock-sales-report.cy.js)
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/ssr-stock-sales-report.cy.js --headed --browser chrome
 */
const RUN = `${Date.now()}`.slice(-8)
const today = () => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const list = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return [] }
const shot = (name) => { if (Cypress.env('capture')) cy.screenshot(name, { capture: 'viewport', overwrite: true }) }

const SUP = 'SSR_Sup_' + RUN, PNAME = 'SSR_Prod_' + RUN, BILL = 'SSR-P-' + RUN
let supplierId = null, productId = null

const report = (q) => cy.request({ url: '/stockSalesReport', qs: q, failOnStatusCode: false }).its('body')
const myRow = (rep) => {
  for (const g of rep.groups || []) for (const r of g.rows || []) if (r.productId === productId) return { g, r }
  return {}
}

describe('SSR — Stock & Sales Report from the stock ledger', () => {
  before(() => {
    cy.loginAsOwner()
    // ── setup: a supplier and a new product of our own ──
    cy.ensureCompany().then((companyId) => {
      cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
        body: { name: SUP, companyId, mobile: '0306' + RUN.slice(-7), email: `ssr${RUN}@t.com` } })
      cy.request('/getUserVenders').then((vr) => {
        const html = String(vr.body && vr.body.object != null ? vr.body.object : vr.body)
        supplierId = Number((new RegExp('<option value=(\\d+)[^>]*>' + SUP).exec(html) || [])[1])
        expect(supplierId, 'spec supplier created').to.be.greaterThan(0)
      })
    })
    cy.seedProduct({ name: PNAME, sellingPrice: 70 }).then((p) => { productId = p.productId })

    // ── S1 purchase 100 @ 50 from the supplier ──
    cy.then(() => cy.request({ method: 'POST', url: '/addPurchase', form: true, body: {
      productId, venderId: supplierId, quantity: 100, purchaseRate: 50, 'stock.bpurchaseRate': 50,
      'stock.bsellRate': 70, 'stock.batchNo': BILL, totalAmount: 5000, netAmount: 2000, paidAmount: 5000,
      purchaseInvoiceNo: BILL } }).its('body.status').should('eq', 'SUCCESS'))

    // ── S2 sell 30 @ 70 ──
    cy.then(() => cy.request({ method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, body: {
      customer: { name: 'SSR_Cust_' + RUN, contact: '0300' + RUN.slice(-7) },
      sales: [{ productId, quantity: 30, sellRate: 70, totalAmount: 2100 }],
      tenders: [{ method: 'CASH', amount: 2100 }], paidAmount: 2100, grandTotal: 2100,
      idempotencyKey: 'cy-ssr-' + RUN } }).its('body.status').should('eq', 'SUCCESS'))

    // ── S3 the customer returns 5 ──
    cy.then(() => cy.request('/getUserSell?q=-1').then((r) => {
      const line = list(r.body).find((s) => s.productId === productId)
      expect(line, 'the sale line').to.exist
      cy.request({ method: 'POST', url: '/saleReturn', form: true,
        body: { sellId: line.sellId, quantity: 5, reason: 'SSR gate' } }).its('body.status').should('eq', 'SUCCESS')
    }))

    // ── S4 ten go back to the supplier ──
    cy.then(() => cy.request('/getUserPurchase').then((r) => {
      const bill = list(r.body).find((p) => p.purchaseInvoiceNo === BILL)
      expect(bill, 'the bill').to.exist
      cy.request({ method: 'POST', url: '/purchaseReturn', form: true,
        body: { purchaseId: bill.purchaseId, quantity: 10, reason: 'SSR gate' } }).its('body.status').should('eq', 'SUCCESS')
    }))

    // ── S5 a correction: two damaged ──
    cy.then(() => cy.request({ method: 'POST', url: '/adjustProductStock', headers: { 'Content-Type': 'application/json' },
      body: { productId, adjustmentType: 'DECREASE', quantity: 2, reason: 'Damaged', idempotencyKey: 'cy-ssr-adj-' + RUN } })
      .its('body.success').should('eq', true))
  })

  beforeEach(() => cy.loginAsOwner())

  it('R1 the bill’s supplier is the product’s main supplier, and the product form shows it', () => {
    cy.request('/getCatalogProduct?id=' + productId).then((r) => {
      const p = (r.body && (r.body.data || r.body)) || {}
      expect(p.mainSupplierId, 'stamped by the first purchase').to.eq(supplierId)
    })
  })

  it('R2 the report row, figure by figure, under its supplier, with subtotal and Net Total', () => {
    report({ from: today(), to: today(), supplierId }).then((rep) => {
      expect(rep.success, JSON.stringify(rep).slice(0, 200)).to.eq(true)
      const { g, r } = myRow(rep)
      expect(r, 'our product is in the report').to.exist
      expect(g.supplier).to.eq(SUP)
      const n = (k) => Number(r[k])
      expect(n('opening'), 'opening').to.eq(0)
      expect(n('purchase'), 'purchase').to.eq(100)
      expect(n('purchaseReturn'), 'purchase return').to.eq(10)
      expect(n('totalStock'), 'total stock').to.eq(90)
      expect(n('salesQty'), 'sales qty').to.eq(30)
      expect(n('salesValue'), 'sales value (gross: the returned 5 added back)').to.eq(2100)
      expect(n('salesReturnQty'), 'return qty').to.eq(5)
      expect(n('salesReturnValue'), 'return value').to.eq(350)
      expect(n('netSalesQty'), 'net qty').to.eq(25)
      expect(n('netSalesValue'), 'net value').to.eq(1750)
      expect(n('bonusQty'), 'bonus').to.eq(0)
      expect(n('adjustments'), 'adjustments').to.eq(-2)
      expect(n('closingQty'), 'closing').to.eq(63)
      expect(n('tp'), 'TP = the last purchase rate').to.eq(50)
      expect(n('closingValue'), 'closing at TP').to.eq(3150)
      expect(r.adds, 'the row adds up').to.eq(true)
      expect(Number(g.total.closingQty), 'subtotal').to.be.at.least(63)
      expect(Number(rep.netTotal.closingQty), 'net total').to.be.at.least(63)
    })
    cy.request(`/productStock?productId=${productId}`).then((s) => expect(Number(s.body.stock), 'on hand agrees').to.eq(63))
  })

  it('R3 the screen: Finance → Stock & Sales, period and supplier, Show', () => {
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.get('#navSSR').click({ force: true })
    cy.get('#SSRDiv').should('be.visible')
    shot('ssr-01-screen-opened')
    cy.get('#ssrFrom').invoke('val', today())
    cy.get('#ssrTo').invoke('val', today())
    cy.get('#ssrSupplier option').should('have.length.greaterThan', 1)
    cy.get('#ssrSupplier').select(String(supplierId), { force: true })
    shot('ssr-02-period-and-supplier')
    cy.intercept('GET', '**/stockSalesReport*').as('ssr')
    cy.get('#ssrLoad').click()
    cy.wait('@ssr')
    cy.contains('#ssrBody tr.ssr-group', SUP).should('exist')
    cy.get(`#ssrBody tr.ssr-row[data-product="${productId}"]`).within(() => {
      cy.get('td[data-k="name"]').should('have.text', PNAME)
      cy.get('td[data-k="purchase"]').should('have.text', '100')
      cy.get('td[data-k="salesQty"]').should('have.text', '30')
      cy.get('td[data-k="netSalesQty"]').should('have.text', '25')
      cy.get('td[data-k="closingQty"]').should('have.text', '63')
      cy.get('td[data-k="closingValue"]').should('have.text', '3,150')
    })
    cy.get('#ssrBody tr.ssr-subtotal').should('have.length.at.least', 1)
    cy.get('#ssrBody tr.ssr-nettotal').should('have.length', 1)
    shot('ssr-03-report-row')
    // the report is 19 columns wide: scroll the table to its right edge so the closing figures are on screen too
    cy.get('#SSRDiv .table-scroll').scrollTo('right', { ensureScrollable: false })
    cy.get(`#ssrBody tr.ssr-row[data-product="${productId}"] td[data-k="closingQty"]`).should('have.text', '63')
    shot('ssr-04-closing-columns')
  })

  it('R4 PDF and CSV are built from what is on screen', () => {
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((w) => w.showSSR())
    cy.get('#ssrFrom').invoke('val', today())
    cy.get('#ssrTo').invoke('val', today())
    cy.get('#ssrSupplier option').should('have.length.greaterThan', 1)
    cy.get('#ssrSupplier').select(String(supplierId), { force: true })
    cy.intercept('GET', '**/stockSalesReport*').as('ssr')
    cy.get('#ssrLoad').click()
    cy.wait('@ssr')
    cy.get('#ssrPdf').should('not.be.disabled')
    cy.window().then((w) => {
      cy.stub(w.URL, 'createObjectURL').returns('blob:ssr').as('blob')
      expect(w.ssrLastReport().groups.length, 'the report on screen').to.be.greaterThan(0)
    })
    cy.get('#ssrCsv').click()
    cy.get('@blob').should('have.been.calledOnce')
    cy.window().then((w) => w.LazyExport.ensurePdfMake().then(() => {
      const create = cy.stub(w.pdfMake, 'createPdf').returns({ download: cy.stub().as('dl') }).as('pdf')
      w.printSSR()
      return create
    }))
    cy.get('@pdf').should('have.been.calledOnce').then((s) => {
      const def = s.args[0][0]
      expect(def.pageOrientation).to.eq('landscape')
      const text = JSON.stringify(def.content)
      expect(text, 'the PDF carries the supplier and the product').to.contain(SUP).and.to.contain(PNAME)
    })
    cy.get('@dl').should('have.been.calledOnce')
  })

  it('R5 a period that ends before it starts is refused with a reason', () => {
    report({ from: today(), to: '2020-01-01' }).then((rep) => {
      expect(rep.success).to.eq(false)
      expect(String(rep.message)).to.match(/period/i)
    })
  })

  it('R6 a plain user cannot read the report, and has no menu entry', () => {
    cy.loginAsTier('user', 'business')
    cy.request({ url: '/stockSalesReport', qs: { from: today(), to: today() }, failOnStatusCode: false }).then((r) => {
      const refused = r.status === 403 || (r.body && r.body.success === false)
      expect(refused, `refused: ${r.status} ${JSON.stringify(r.body).slice(0, 120)}`).to.eq(true)
    })
    cy.visit('/businessDashboard')
    cy.get('#navSSR').should('not.exist')
  })
})
