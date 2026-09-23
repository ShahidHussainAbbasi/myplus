/**
 * COGS-2 — the sale report says what a sale COST and what it MADE.
 *
 * Raised from a real invoice: INV-000011, org 13, goods 700, a whole-invoice trade discount of 20, cost 600.
 * The owner's question was "shouldn't the 20 come off the profit?" — and nothing on the sale report answered
 * it, because the grid showed what each line was CHARGED and never what it cost.
 *
 * Two separate defects sat behind that question, and only one of them was about reporting:
 *
 *   1. ⚠ MONEY. SaleCosting skipped any FEFO pick whose batch carried no purchase price, and fell back to the
 *      line's cost snapshot only when NO pick had a cost. A sale mixing the two posted a PARTIAL cost in
 *      silence. On INV-000011 that booked COGS of 350 against a true 600 — a reported profit of 330 where 80
 *      was right. The trial balance still balanced, because the entry was internally consistent.
 *   2. The grid could not show profit at all, so nobody could have noticed.
 *
 * ⚠ THE CASE THAT CARRIES THIS is 2: the Profit column must SUM ACROSS AN INVOICE to revenue − discount −
 * cost. Per-line profit alone would pass while ignoring the concession entirely, which is precisely the
 * question that prompted the work.
 *
 * ⚠ RUN BEFORE REBUILDING — the served grid has no Cost/Invoice Disc./Profit columns, so these must fail.
 *
 * Run headed, solo:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/sale-report-profit.cy.js
 */

const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

/** Two products at a known cost, sold at a known price — so every figure below is arithmetic, not a lookup. */
const COST_A = 250, PRICE_A = 300
const COST_B = 350, PRICE_B = 400
const TRADE_DISCOUNT = 20
// goods 700, cost 600, concession 20 → the true profit of the invoice this slice was raised from.
const TRUE_PROFIT = (PRICE_A + PRICE_B) - (COST_A + COST_B) - TRADE_DISCOUNT

const makeProduct = (price) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: { name: `CG2 ${uniq()}`, sku: `CG2${uniq()}`, sellingPrice: price, taxRate: 0, unit: 'pcs' },
  }).then((r) => {
    expect(r.body.success, `addProduct: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)
    return cy.wrap(r.body.data.id)
  })

/** Stock it in at a KNOWN cost — this is what makes COGS predictable rather than inherited. */
const stockIn = (productId, qty, cost, sellPrice) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: { productId, quantity: qty, 'stock.batchNo': `CG2B${uniq()}`,
      'stock.bpurchaseRate': cost, 'stock.bsellRate': sellPrice,
      totalAmount: qty * cost, netAmount: qty * cost, purchaseInvoiceNo: `CG2-${uniq()}` },
  }).then((r) => expect(r.body.status, `stock in: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS'))

const num = (s) => Number(String(s == null ? '' : s).replace(/[^0-9.\-]/g, '')) || 0

/** The rows of #tableSell for one invoice, read through the DataTables API rather than the painted page. */
const rowsFor = (invoiceNo) =>
  cy.window().its('datatable').then((dt) => {
    const rows = dt.rows().data().toArray().filter((r) => String(r[1]).indexOf(invoiceNo) >= 0)
    expect(rows.length, `rows for ${invoiceNo} are in the grid`).to.be.greaterThan(0)
    return rows
  })

describe('COGS-2 — the sale report shows cost and true profit', () => {
  let invoiceNo = null

  before(() => {
    cy.loginAsBusiness()
    makeProduct(PRICE_A).then((a) => {
      stockIn(a, 5, COST_A, PRICE_A)
      makeProduct(PRICE_B).then((b) => {
        stockIn(b, 5, COST_B, PRICE_B)
        const total = PRICE_A + PRICE_B - TRADE_DISCOUNT
        cy.request({
          method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
          failOnStatusCode: false,
          body: {
            customer: { name: `CG2_${uniq()}`, contact: '03009999999' },
            sales: [
              { productId: a, itemName: 'CG2 A', quantity: 1, sellRate: PRICE_A },
              { productId: b, itemName: 'CG2 B', quantity: 1, sellRate: PRICE_B },
            ],
            tradeDiscount: TRADE_DISCOUNT,
            tenders: [{ method: 'CASH', amount: total }],
            paidAmount: total, grandTotal: total,
            idempotencyKey: `cy-cg2-${uniq()}`,
          },
        }).then((r) => {
          expect(r.body.status, `the sale: ${JSON.stringify(r.body).slice(0, 250)}`).to.eq('SUCCESS')
          invoiceNo = (r.body.object && (r.body.object.invoiceNo || r.body.object)) || null
          expect(invoiceNo, 'the sale returned its invoice number').to.be.a('string')
        })
      })
    })
  })

  beforeEach(() => cy.loginAsBusiness())

  it('⭐ 1 — the grid shows what each line COST', () => {
    cy.openSellSection('sellDiv')
    cy.get('#tableSell', { timeout: 20000 }).should('exist')
    cy.get('#tableSell_filter input', { timeout: 15000 }).clear().type(invoiceNo)

    rowsFor(invoiceNo).then((rows) => {
      const costs = rows.map((r) => num(r[9])).sort((x, y) => x - y)
      expect(costs, 'the two lines cost 250 and 350').to.deep.eq([COST_A, COST_B])
    })
  })

  it('⭐⭐ 2 — THE ONE THAT MATTERS: Profit SUMS to revenue − discount − cost', () => {
    /*
     * Per-line profit alone would pass while ignoring the concession — the exact gap that prompted this.
     * The invoice's profit is what the owner asked about, so the invoice's profit is what is asserted.
     */
    cy.openSellSection('sellDiv')
    cy.get('#tableSell', { timeout: 20000 }).should('exist')
    cy.get('#tableSell_filter input', { timeout: 15000 }).clear().type(invoiceNo)

    rowsFor(invoiceNo).then((rows) => {
      const profit = rows.reduce((a, r) => a + num(r[11]), 0)
      expect(Math.round(profit * 100) / 100,
        `700 goods − 20 discount − 600 cost = ${TRUE_PROFIT}; the defect reported 330`)
        .to.eq(TRUE_PROFIT)
    })
  })

  it('⭐ 3 — the invoice discount is shown as the INVOICE\'s, not as a line discount', () => {
    cy.openSellSection('sellDiv')
    cy.get('#tableSell', { timeout: 20000 }).should('exist')
    cy.get('#tableSell_filter input', { timeout: 15000 }).clear().type(invoiceNo)

    rowsFor(invoiceNo).then((rows) => {
      rows.forEach((r) => {
        expect(num(r[10]), 'the same whole-invoice figure on every line').to.eq(TRADE_DISCOUNT)
      })
      // And the per-LINE discount column is untouched — the two are different things.
      rows.forEach((r) => expect(num(r[6]), 'no per-line discount was given').to.eq(0))
    })
  })

  it('4 — the grid is reachable on a narrow screen, not clipped', () => {
    // responsive-tables.js wraps every #content table in .table-scroll and re-wraps after each DataTables
    // draw. Three more columns is exactly what makes a grid outgrow a laptop, so the wrapper is asserted
    // rather than assumed.
    cy.viewport(900, 800)
    cy.openSellSection('sellDiv')
    cy.get('#tableSell', { timeout: 20000 }).should('exist')
    cy.get('#tableSell').parent().should('have.class', 'table-scroll')
    cy.get('#tableSell').parent().then(($w) => {
      expect(['auto', 'scroll'], 'the wrapper actually scrolls')
        .to.include(getComputedStyle($w[0]).overflowX)
    })
  })
})
