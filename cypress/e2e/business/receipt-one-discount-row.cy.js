/**
 * ONE-DISCOUNT-ROW — the till slip shows every discount in ONE row, and no row when there is none.
 *
 * THE DEFECT (INV-000050's printed dispense receipt): a 72.00 line over "Subtotal 70.00", TOTAL 70.00, and no
 * discount anywhere — the 2.00 trade discount showed up only as "Change 2.00". The rule (user, 2026-09-24):
 * line discounts + trade discount = ONE Discount row; absent when zero. And the slip must add up:
 *     Subtotal (Qty × Rate, gross) − Discount (+ Tax on top) (+ Delivery) = TOTAL
 *
 * Asserted on DocumentRenderer.toPrintModel — the SAME resolved model the paper, the PDF and the ESC/POS
 * printer all draw from — plus one real sale end to end.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/receipt-one-discount-row.cy.js --headed --browser chrome
 */
const uniq = () => `${Date.now()}`.slice(-8) + Math.floor(Math.random() * 900 + 100)
/** The NUMBER in a printed value — "Rs.185.00", "-2.00", "1,234.50". Stripping every non-digit kept the dot
 *  after "Rs" and read "Rs.185.00" as ".185.00" = NaN. */
const num = (s) => {
  const m = String(s).match(/-?\d[\d,]*(?:\.\d+)?/)
  return m ? Number(m[0].replace(/,/g, '')) : NaN
}

const rows = (model) => {
  const out = {}
  model.totals.forEach((r) => { out[r.key] = r })
  return out
}

/** The totals a slip prints must reach its TOTAL — the arithmetic a customer checks. */
const assertAddsUp = (model, taxOnTop) => {
  const r = rows(model)
  const sub = num(r.subTotalGross.value)
  const disc = r.totalDiscount ? Math.abs(num(r.totalDiscount.value)) : 0
  const tax = (taxOnTop && r.taxTotal) ? num(r.taxTotal.value) : 0
  const ship = r.shippingFee ? num(r.shippingFee.value) : 0
  expect(Math.round((sub - disc + tax + ship) * 100) / 100, 'Subtotal − Discount + Tax + Delivery = TOTAL')
    .to.eq(num(r.grandTotal.value))
}

describe('ONE-DISCOUNT-ROW — one Discount row, none when zero, and the slip adds up', () => {
  beforeEach(() => {
    cy.loginAsOwner()
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
  })

  const slip = (w, inv, preset = 'RETAIL_RECEIPT_80MM') =>
    w.DocumentRenderer.toPrintModel(inv, w.DocumentRenderer.PRESETS[preset])

  it('⭐⭐ 1 — INV-000050 as stored: Subtotal 72 · Discount -2 · TOTAL 70, on BOTH 80mm slips', () => {
    // The exact stored figures of the reported invoice (org 15, id 10830).
    const inv = {
      invoiceNo: 'INV-000050', dated: '2026-09-23T15:28:12', subTotal: 70, taxTotal: 0, tradeDiscount: 2,
      grandTotal: 70, tenderedAmount: 72, changeAmount: 2, paymentMode: 'CASH',
      sales: [{ itemName: 'Brufin 500mg', quantity: 0.3, sellRate: 240, totalAmount: 72, discount: 0,
        taxAmount: 0, soldUnit: 'LOOSE', soldQuantity: 6, soldRate: 12, packSizeSnapshot: 20 }],
    }
    cy.window().then((w) => {
      ;['RETAIL_RECEIPT_80MM', 'DISPENSE_RECEIPT_80MM'].forEach((p) => {
        const m = slip(w, inv, p)
        const r = rows(m)
        expect(num(r.subTotalGross.value), `${p}: Subtotal is the GROSS goods`).to.eq(72)
        expect(r.totalDiscount, `${p}: ⭐ the discount is ON the slip`).to.exist
        expect(num(r.totalDiscount.value), `${p}: one row, the trade discount`).to.eq(-2)
        expect(num(r.grandTotal.value)).to.eq(70)
        expect(m.rows[0][m.columns.findIndex((c) => c.key === 'lineAmount')], `${p}: 6 × 12.00 = 72.00`).to.eq('72.00')
        assertAddsUp(m, true)
      })
    })
  })

  it('⭐ 2 — line discounts AND trade discount print as ONE row', () => {
    const inv = {
      invoiceNo: 'T2', subTotal: 150, taxTotal: 0, tradeDiscount: 20, grandTotal: 150,
      sales: [
        { itemName: 'A', quantity: 2, sellRate: 50, totalAmount: 100, discount: 10, taxAmount: 0 },
        { itemName: 'B', quantity: 1, sellRate: 80, totalAmount: 80, discount: 0, taxAmount: 0 },
      ],
    }
    cy.window().then((w) => {
      const m = slip(w, inv)
      const keys = m.totals.map((t) => t.key)
      expect(keys.filter((k) => /discount/i.test(k)), 'exactly ONE discount row').to.deep.eq(['totalDiscount'])
      expect(num(rows(m).totalDiscount.value), '10 on the line + 20 on the invoice').to.eq(-30)
      expect(m.columns.map((c) => c.key), 'no per-line Disc column').to.not.include('discount')
      assertAddsUp(m, true)
    })
  })

  it('⭐ 3 — no discount of any kind: NO Discount row', () => {
    const inv = {
      invoiceNo: 'T3', subTotal: 100, taxTotal: 0, grandTotal: 100,
      sales: [{ itemName: 'A', quantity: 1, sellRate: 100, totalAmount: 100, discount: 0, taxAmount: 0 }],
    }
    cy.window().then((w) => {
      const m = slip(w, inv)
      expect(m.totals.map((t) => t.key), 'the row does not print at all').to.not.include('totalDiscount')
      assertAddsUp(m, true)
    })
  })

  it('4 — tax ON TOP: added, and the slip still adds up', () => {
    // 100 − 10 disc = 90 base, 17% = 15.30 tax, grand 105.30.
    const inv = {
      invoiceNo: 'T4', subTotal: 90, taxTotal: 15.3, grandTotal: 105.3, taxLabel: 'GST',
      sales: [{ itemName: 'A', quantity: 1, sellRate: 100, totalAmount: 100, discount: 10, taxAmount: 15.3, taxRate: 17 }],
    }
    cy.window().then((w) => {
      const m = slip(w, inv)
      const tax = m.totals.find((t) => t.key === 'taxTotal')
      expect(tax.label, 'plain tax label when it is charged on top').to.eq('GST')
      assertAddsUp(m, true)
    })
  })

  it('⭐ 5 — tax INSIDE the price: labelled "incl.", NOT added, and the line is not inflated', () => {
    // Inclusive: 100 − 10 = 90 charged, of which 13.08 is tax. grand = 90.
    const inv = {
      invoiceNo: 'T5', subTotal: 76.92, taxTotal: 13.08, grandTotal: 90, taxLabel: 'GST',
      sales: [{ itemName: 'A', quantity: 1, sellRate: 100, totalAmount: 100, discount: 10, taxAmount: 13.08, taxRate: 17 }],
    }
    cy.window().then((w) => {
      const m = slip(w, inv)
      const tax = m.totals.find((t) => t.key === 'taxTotal')
      expect(tax.label, 'tax already in the price is information').to.match(/^GST \(.+\)$/)
      assertAddsUp(m, false)
      // The A4 invoice's Total column was value − disc + tax = 103.08 on an inclusive tenant: the defect.
      const a4 = w.DocumentRenderer.toPrintModel(inv, w.DocumentRenderer.PRESETS.TRADE_INVOICE_A4)
      const i = a4.columns.findIndex((c) => c.key === 'lineTotal')
      expect(a4.rows[0][i], 'the line total is what was charged, not tax on top of tax').to.eq('90.00')
    })
  })

  it('⭐⭐ 6 — end to end: a real sale with a line discount and a trade discount prints one row and adds up', () => {
    cy.seedProduct({ name: `ODR_${uniq()}`, sellingPrice: 100, stock: 10 }).then(({ productId }) => {
      // Re-open the till AFTER seeding: beforeEach loaded it before this product existed, so its picker lacked it.
      cy.visit('/businessDashboard')
      cy.waitForAppReady()
      cy.window().its('posConfirmOnComplete', { timeout: 15000 }).should('eq', true)
      cy.get('#sellType').select('sellDiv', { force: true })
      cy.get('#sellItemDD', { timeout: 15000 }).select(String(productId), { force: true })
      cy.get('#sellQuantity', { timeout: 15000 }).should(($q) => expect($q.val()).not.to.eq(''))
      cy.get('#sellQuantity').clear().type('2').should('have.value', '2')
      cy.get('#sellDiscountTypeDD').select('0', { force: true })                  // a flat AMOUNT, not %
      cy.get('#sellDiscount').clear({ force: true }).type('10', { force: true })   // a 10.00 line discount
      cy.get('#addInviceItem').click({ force: true })
      cy.window().its('data').should('have.length', 1)
      cy.get('#sellTradeDiscount').clear({ force: true }).type('5', { force: true })
      cy.get('#sellPayable').should('have.text', '185.00')                        // 200 − 10 − 5
      cy.get('#sellPayMethod').select('CASH', { force: true })
      cy.get('#sellRec').clear().type('185')

      cy.intercept('POST', '**/addSell').as('sale')
      cy.get('#addSell').click({ force: true })
      cy.confirmSale()
      cy.wait('@sale', { timeout: 20000 }).then((i) => {
        expect(i.response.body.status, JSON.stringify(i.response.body)).to.eq('SUCCESS')
        cy.request(`/getReceipt?invoiceNo=${encodeURIComponent(i.response.body.object)}`).then((r) => {
          const inv = r.body.object
          expect(Number(inv.grandTotal)).to.eq(185)
          expect(Number(inv.changeAmount || 0), 'no phantom change').to.eq(0)
          cy.window().then((w) => {
            // The retail slip explicitly: an owner's stored template must not decide which document is gated.
            const m = w.DocumentRenderer.toPrintModel(inv, w.DocumentRenderer.PRESETS.RETAIL_RECEIPT_80MM)
            const rr = rows(m)
            expect(num(rr.subTotalGross.value), 'Subtotal 2 × 100').to.eq(200)
            expect(num(rr.totalDiscount.value), 'ONE row: 10 line + 5 trade').to.eq(-15)
            assertAddsUp(m, true)
          })
        })
      })
    })
  })
  /*
   * RCPT-LBL — INV-000054 (org 15): "Previous balance 0.00 / New balance 0.00" under a walk-in cash sale read as an
   * account the customer does not have, and "Tendered" is jargon at a counter. Account lines print on a till slip
   * only when there IS a balance; the A4 trade invoice keeps them at zero (its buyer reconciles every invoice).
   */
  const cashSale = (balanceAfter, dueAmount) => ({
    invoiceNo: 'RB', subTotal: 42, taxTotal: 0, tradeDiscount: 3, grandTotal: 42, paymentMode: 'CASH',
    tenderedAmount: 272, changeAmount: 230, dueAmount: dueAmount, balanceAfter: balanceAfter,
    sales: [{ itemName: 'A', quantity: 1, sellRate: 50, totalAmount: 50, discount: 5, taxAmount: 0 }],
  })

  it('⭐ 8 — a walk-in slip with no account balance prints NO Previous/New balance lines', () => {
    cy.window().then((w) => {
      const keys = slip(w, cashSale(0, 0)).totals.map((t) => t.key)
      expect(keys, 'no account lines for a customer with no account balance')
        .to.not.include.members(['previousBalance', 'currentBalance'])
      expect(keys, "the cash lines stay — they are the customer's proof of change").to.include.members(['paidBy', 'tendered', 'change'])
    })
  })

  it('9 — a customer WITH a balance still sees both account lines on the slip', () => {
    cy.window().then((w) => {
      const keys = slip(w, cashSale(150, 0)).totals.map((t) => t.key)
      expect(keys).to.include.members(['previousBalance', 'currentBalance'])
    })
  })

  it('10 — the A4 trade invoice keeps its account lines even at zero', () => {
    cy.window().then((w) => {
      const m = w.DocumentRenderer.toPrintModel(cashSale(0, 0), w.DocumentRenderer.PRESETS.TRADE_INVOICE_A4)
      const keys = m.totals.map((t) => t.key)
      if (w.DocumentRenderer.PRESETS.TRADE_INVOICE_A4.totals.indexOf('currentBalance') !== -1) {
        expect(keys, 'unchanged for the trade document').to.include('currentBalance')
      }
    })
  })

  it('11 — English labels read as plain words: "Amount received", "Change returned"', () => {
    cy.window().then((w) => {
      if (String(w.document.documentElement.lang || 'en').slice(0, 2) !== 'en') return   // English run only
      const rows = slip(w, cashSale(0, 0)).totals
      const byKey = {}
      rows.forEach((r) => { byKey[r.key] = r })
      expect(byKey.tendered.label).to.eq('Amount received')
      expect(byKey.change.label).to.eq('Change returned')
    })
  })
})
