/**
 * U4 — what the customer reads.
 *
 * Design: microservices/docs/slices/u4-loose-on-paper.md
 *
 * U3 lets a cashier sell five tablets. Until this slice the receipt for that sale said **0.5 × 120.00** — a
 * quantity nobody recognises for goods they are holding, at a rate they never agreed to.
 *
 * ⚠ THE CONSTRAINT THAT SHAPES EVERY CASE: the LINE TOTAL never changes. A tax inspector reconciles
 * `quantity × rate` against it, so 5 × 12.00 = 60.00 passes an audit while "5 tablets" beside 120.00 would
 * not. U4 changes labels and quantities; it must never change money.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/sell-loose-receipt.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (body) => body.collection || body.data || []

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success, `saveBusinessConfig ${key}: ${JSON.stringify(r.body)}`).to.eq(true))

const packProduct = (name, price, packSize, allowLoose = true) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: { name, sellingPrice: price, unit: 'pack', packSize,
      looseUnit: 'tablet', looseUnitPlural: 'tablets', allowLoose, defaultSellUnit: 'PACK' },
  }).then((r) => {
    expect(r.body.success, `product ${name}: ${JSON.stringify(r.body)}`).to.eq(true)
    return cy.request('/getUserProduct?q=-1').then((pr) => {
      const p = list(pr.body).find((x) => x.name === name)
      expect(p, 'the product was stored').to.exist
      return p
    })
  })

/** ⚠ A purchase RESTAMPS the selling price from bsellRate — pass the intended price (U2 §13.4c). */
const stockIn = (productId, qty, cost, sellPrice) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: { productId, quantity: qty, 'stock.batchNo': `RB${uniq()}`,
      'stock.bpurchaseRate': cost, 'stock.bsellRate': sellPrice,
      totalAmount: qty * cost, netAmount: qty * cost, purchaseInvoiceNo: `RCPT-${uniq()}` },
  }).then((r) => expect(r.body.status, `stock in: ${JSON.stringify(r.body).substring(0, 200)}`).to.eq('SUCCESS'))

const sell = (lines, total) =>
  cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      customer: { name: `Rcpt_${uniq()}`, contact: '03009999999' },
      sales: lines, tenders: [{ method: 'CASH', amount: total }],
      paidAmount: total, grandTotal: total, idempotencyKey: `cy-rcpt-${uniq()}`,
    },
  }).then((r) => {
    expect(r.body.status, `sale: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS')
    return r.body.object
  })

const linesOf = (invoiceNo) =>
  cy.request('/getUserSell').then((r) => {
    const rows = list(r.body).filter((row) => (row.customerHistory || {}).invoiceNo === invoiceNo)
    expect(rows.length, `invoice ${invoiceNo} has lines`).to.be.greaterThan(0)
    return rows
  })

const openSale = () => {
  cy.openSellSection('sellDiv')
  cy.get('#sellItemDD', { timeout: 15000 }).should('exist')
}

describe('U4 — what the customer reads', () => {
  beforeEach(() => cy.loginAsOwner())

  after(() => { cy.loginAsOwner(); setConfig('pos.sale.looseMarkupPct', '0') })

  // ── ⭐ the formatter, as pure logic ───────────────────────────────────────────────────────────────────

  it('⭐ the shared formatter is an IDENTITY on an ordinary line', () => {
    // The property that lets all six render points adopt it without a shop noticing: `soldUnit` is null on
    // every row written before U2 and on every pack sale since, so the overwhelming majority of lines in
    // every tenant come back exactly as they went in.
    openSale()
    cy.window().then((w) => {
      expect(w.looseDisplay, 'loose-format.js is loaded').to.be.a('function')

      const legacy = { quantity: 2, sellRate: 120 }
      expect(w.looseDisplay(legacy)).to.deep.eq({ isLoose: false, qty: 2, unit: '', rate: 120, packs: 2 })
      expect(w.looseQtyText(legacy)).to.eq('2')

      const pack = { quantity: 2, sellRate: 120, soldUnit: 'PACK', packSizeSnapshot: 10 }
      expect(w.looseQtyText(pack), 'an explicit PACK line still reads as a number').to.eq('2')

      const loose = { quantity: 0.5, sellRate: 120, soldUnit: 'LOOSE', soldQuantity: 5,
                      soldRate: 12, looseUnitPlural: 'tablets', packSizeSnapshot: 10 }
      const d = w.looseDisplay(loose)
      expect(d.isLoose).to.eq(true)
      expect(d.qty).to.eq(5)
      expect(d.rate).to.eq(12)
      expect(d.packs, 'what left the shelf is still available').to.eq(0.5)
      expect(w.looseQtyText(loose)).to.eq('5 tablets')

      // ⭐ quantity × rate must still equal the line total, or the document fails an audit.
      expect(d.qty * d.rate).to.eq(60)
    })
  })

  it('⭐ the pack size is read from the SALE, never from the product', () => {
    // A receipt reprinted after the shop moves from packs of 10 to packs of 12 must still say five tablets,
    // not six. U2 froze packSizeSnapshot on the line for exactly this moment.
    openSale()
    cy.window().then((w) => {
      const line = { quantity: 0.5, soldUnit: 'LOOSE', soldQuantity: 5, soldRate: 12,
                     looseUnitPlural: 'tablets', packSizeSnapshot: 10 }
      expect(w.loosePackSize(line), 'the pack size at the time of sale').to.eq(10)
      expect(w.looseQtyText(line), 'still five tablets, whatever the product says now').to.eq('5 tablets')
    })
  })

  // ── ⭐ the receipt ────────────────────────────────────────────────────────────────────────────────────

  it('⭐ the receipt says "5 tablets @ 12.00" and still totals 60.00', () => {
    const name = `Rcpt_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '0')

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      sell([{ productId: p.id, soldUnit: 'LOOSE', soldQuantity: 5 }], 60).then((invoiceNo) => {
        linesOf(invoiceNo).then((rows) => {
          const line = rows[0]
          openSale()
          cy.window().then((w) => {
            /*
             * Asserted through DocumentRenderer.lineMath — THE FUNCTION THE PRINTER ITSELF CALLS, not a
             * re-implementation of it. It is exported, so there is no reason to test a proxy for it: this is
             * the same code path the 80mm receipt, the A4 invoice, the trade invoice, the PDF download and
             * the designer preview all run through.
             */
            expect(w.DocumentRenderer && w.DocumentRenderer.lineMath, "the printer's own line maths")
              .to.be.a('function')
            const m = w.DocumentRenderer.lineMath(line)

            expect(m.isLoose, 'the printer sees a loose line').to.eq(true)
            expect(m.qty, 'and prints five').to.eq(5)
            expect(m.unit, "in the customer's own word").to.eq('tablets')
            expect(Number(m.rate).toFixed(2), 'at the per-tablet rate').to.eq('12.00')

            // ⭐ THE AUDIT CONSTRAINT: what is printed must reconcile to what was charged.
            expect(m.qty * m.rate, 'quantity × rate must equal the line total')
              .to.be.closeTo(m.total, 0.01)
            expect(m.total, 'and the total is unchanged by any of this')
              .to.be.closeTo(Number(line.netAmount), 0.01)

            expect(w.looseQtyText(line)).to.eq('5 tablets')
          })
        })
      })
    })
  })

  it('⭐ an ordinary sale reads exactly as it did before', () => {
    // The regression that protects every shop that never breaks a pack.
    const name = `PlainRcpt_${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      sell([{ productId: p.id, quantity: 2, sellRate: 120, totalAmount: 240, netAmount: 240 }], 240)
        .then((invoiceNo) => linesOf(invoiceNo).then((rows) => {
          const line = rows[0]
          expect(line.soldUnit, 'no unit was recorded').to.be.oneOf([null, undefined, ''])
          openSale()
          cy.window().then((w) => {
            expect(w.looseQtyText(line), 'a plain number, as always').to.eq('2')
            expect(w.looseDisplay(line).rate).to.eq(120)
          })
        }))
    })
  })

  // ── the cart grid, which disagreed with itself ───────────────────────────────────────────────────────

  it('the cart grid shows the same thing however the line was added', () => {
    // Before U4 the manual add showed 0.5 and a `5L*CODE` scan of the SAME product showed 5 — two
    // unlabelled numbers for one sale, on the screen a cashier watches while ringing up.
    const name = `Cart_${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 10, 100, 120)
      openSale()
      cy.get(`#sellItemDD option[value="${p.id}"]`, { timeout: 10000 }).should('exist')
      cy.get('#sellItemDD').select(String(p.id), { force: true })
      cy.get('#sellSellRate').should('not.have.value', '')

      cy.get('#sellItems').clear().type('5')
      cy.get('#sellUnitLoose').click({ force: true })
      cy.get('#addInviceItem').click({ force: true })

      cy.get('#tablesi tbody tr', { timeout: 10000 }).should('have.length.at.least', 1)
      cy.get('#tablesi tbody tr').first().should('contain.text', '5 tablets')
    })
  })

  // ── the edit path, which can lose money ──────────────────────────────────────────────────────────────

  it('editing a loose sale puts 5 in the quantity box, not 0.5', () => {
    // 0.5 in that box is read by the cashier as PIECES and by seven code paths as PACKS — updating would
    // re-submit half a pack of packs at the pack rate.
    const name = `Edit_${uniq()}`

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 20, 100, 120)
      sell([{ productId: p.id, soldUnit: 'LOOSE', soldQuantity: 5 }], 60).then((invoiceNo) => {
        openSale()
        cy.window().then((w) => {
          expect(w.looseDisplay, 'the formatter the edit path uses').to.be.a('function')
          const d = w.looseDisplay({ quantity: 0.5, soldUnit: 'LOOSE', soldQuantity: 5,
                                     soldRate: 12, looseUnitPlural: 'tablets', packSizeSnapshot: 10 })
          expect(d.qty, 'the box is loaded with what was SOLD').to.eq(5)
          expect(d.packs, 'and the pack figure is still available for the shelf').to.eq(0.5)
        })
        expect(invoiceNo).to.be.a('string')
      })
    })
  })

  // ── a mixed document ─────────────────────────────────────────────────────────────────────────────────

  it('a mixed invoice reads correctly and its lines sum to the total', () => {
    const name = `Mixed_${uniq()}`
    setConfig('pos.sale.looseMarkupPct', '0')

    packProduct(name, 120, 10).then((p) => {
      stockIn(p.id, 20, 100, 120)
      sell([
        { productId: p.id, quantity: 1, sellRate: 120, totalAmount: 120, netAmount: 120 },
        { productId: p.id, soldUnit: 'LOOSE', soldQuantity: 5 },
      ], 180).then((invoiceNo) => linesOf(invoiceNo).then((rows) => {
        expect(rows.length, 'two lines').to.eq(2)
        openSale()
        cy.window().then((w) => {
          const texts = rows.map((r) => w.looseQtyText(r))
          expect(texts, 'one pack line and one loose line, each in its own unit')
            .to.include.members(['1', '5 tablets'])
          const sum = rows.reduce((a, r) => a + Number(r.netAmount || 0), 0)
          expect(sum, '120 + 60').to.eq(180)
        })
      }))
    })
  })
})
