/**
 * B2B Phase 3g-1 / 3g-2 — the printable TRADE INVOICE, and the profile-driven renderer behind it.
 *
 * Phase 3b-1 made a trade sale print the WORD "INVOICE" on an 80mm till slip with four columns. This gate
 * covers the document actually becoming one: A4, the trade columns, the letterhead, the totals band and the
 * account balance in DR/CR — while a walk-in sale keeps printing exactly what it prints today.
 *
 * HOW THE RENDERER IS TESTED: by calling `window.DocumentRenderer.buildHtml` in the real dashboard with a
 * real /getReceipt payload. That is the same function the printer calls, so nothing here is a stand-in — and
 * it means an assertion about what the customer receives is an assertion about production code.
 *
 * Design: microservices/docs/slices/b2b-P3g-trade-invoice-designer.md
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/receipt-trade-invoice.cy.js --headed --no-exit --config screenshotOnRunFailure=false
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => {
  for (const key of ['collection', 'data', 'object']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return Array.isArray(body) ? body : []
}

const receiptOf = (invoiceNo) =>
  cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo)).then((r) => {
    expect(r.body.status, `getReceipt ${invoiceNo}: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
    expect(r.body.object, 'receipt payload').to.exist
    return cy.wrap(r.body.object)
  })

/** SEED a customer of a given channel. Never assert-or-skip: the type is the whole point of the gate. */
const customerOfType = (name, customerType) =>
  cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
    body: { name, contact: 'C' + uniq(), customerType } })
    .then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      return cy.request('/getUserCustomer')
    })
    .then((r) => {
      const c = list(r.body).find((x) => x.name === name)
      expect(c, `customer ${name} created`).to.exist
      expect(String(c.customerType), 'the seeded channel actually stuck').to.eq(customerType)
      return cy.wrap(c)
    })

/**
 * Sell one line. `discount` is posted where the server actually reads it from — `stock.bsellDiscount` —
 * not as a top-level field, which SagaSellService ignores.
 */
const sell = (cust, productId, qty, rate, paid, discount) =>
  cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: {
      customer: { customerId: cust.customerId, name: cust.name, contact: cust.contact },
      sales: [{
        productId, quantity: qty, sellRate: rate,
        totalAmount: qty * rate, netAmount: qty * rate,
        stock: discount ? { bsellDiscount: discount, bsellDiscountType: '0' } : {},
      }],
      tenders: paid > 0 ? [{ method: 'CASH', amount: paid }] : [],
      paidAmount: paid, grandTotal: qty * rate - (discount || 0),
      idempotencyKey: 'cy-3g-' + uniq(),
    },
  }).then((r) => {
    expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
    return cy.wrap(r.body.object)
  })

/** Render a payload through the PRODUCTION renderer, in the real page. */
const render = (payload, profile) =>
  cy.window().then((win) => {
    expect(win.DocumentRenderer, 'DocumentRenderer is loaded').to.exist
    return cy.wrap(win.DocumentRenderer.buildHtml(payload, profile))
  })

describe('B2B P3g — trade invoice + profile-driven renderer', () => {

  beforeEach(() => {
    cy.loginAsOwner()
    cy.visit('/businessDashboard')
  })

  // ── REGRESSION FIRST: a walk-in sale must print exactly what it prints today ──────────────

  it('REGRESSION — a WALK_IN sale still prints the 80mm thermal receipt, not an invoice', () => {
    cy.seedProduct({ name: 'Thermal_' + uniq(), sellingPrice: 50, stock: 20 }).then(({ productId }) =>
      customerOfType('CyWalkin_' + uniq(), 'WALK_IN').then((cust) =>
        sell(cust, productId, 2, 50, 100).then((invoiceNo) =>
          receiptOf(invoiceNo).then((inv) => {
            expect(String(inv.customer.customerType)).to.eq('WALK_IN')
            render(inv).then((html) => {
              expect(html, 'thermal width').to.contain('width:80mm')
              expect(html, 'not an A4 page').to.not.contain('size:A4')
              expect(html, 'a receipt, not an invoice').to.not.contain('>INVOICE<')
            })
          }))))
  })

  // ── the channel switch: a trade account gets the A4 invoice ──────────────────────────────

  it('a RETAILER sale prints an A4 INVOICE with the trade columns', () => {
    const batch = 'B3G-' + uniq()
    cy.seedProduct({ name: 'Trade_' + uniq(), sellingPrice: 70, stock: 40, batchNo: batch })
      .then(({ productId }) =>
        customerOfType('CyTrade_' + uniq(), 'RETAILER').then((cust) =>
          sell(cust, productId, 20, 70, 0).then((invoiceNo) =>
            receiptOf(invoiceNo).then((inv) => {
              render(inv).then((html) => {
                expect(html, 'A4 page').to.contain('size:A4')
                expect(html, 'titled INVOICE').to.contain('INVOICE')
                // The columns the sample invoice has and the old receipt did not.
                for (const col of ['Code', 'Packing', 'Batch No', 'Expiry', 'TP', 'Value', 'D%', 'Net-TP']) {
                  expect(html, `column ${col}`).to.contain(col)
                }
                expect(html, 'the batch is a COLUMN value now').to.contain(batch)
              })
            }))))
  })

  // ── the defect this slice found ───────────────────────────────────────────────────────────

  it('DEFECT FIX — a discounted line prints what the customer was CHARGED, and the lines sum to the total', () => {
    // 10 × 100 = 1000 gross, less a 100 discount = 900. Before 3g the receipt printed
    // `totalAmount + taxAmount` and never read Sell.discount, so this line printed 1000 while the
    // document's own TOTAL said 900 — invisible on any sale without a discount, which is why it survived.
    cy.seedProduct({ name: 'Disc_' + uniq(), sellingPrice: 100, stock: 40 }).then(({ productId }) =>
      customerOfType('CyDisc_' + uniq(), 'RETAILER').then((cust) =>
        sell(cust, productId, 10, 100, 0, 100).then((invoiceNo) =>
          receiptOf(invoiceNo).then((inv) => {
            const line = inv.sales[0]
            expect(Number(line.totalAmount), 'totalAmount is gross, pre-discount').to.eq(1000)
            expect(Number(line.discount), 'the discount was recorded').to.eq(100)

            cy.window().then((win) => {
              const m = win.DocumentRenderer.lineMath(line)
              expect(m.value, 'Value column = gross').to.eq(1000)
              expect(m.discount, 'Discount column').to.eq(100)
              expect(m.total, 'Total column = charged, NOT gross').to.eq(900)
              expect(m.netRate, 'Net-TP = discounted unit rate').to.eq(90)
              // The whole point: the lines add up to the figure printed at the foot.
              expect(m.total, 'lines sum to the invoice total').to.eq(Number(inv.grandTotal))
            })
          }))))
  })

  // ── the letterhead: stop printing OUR brand on THEIR invoice ──────────────────────────────

  it('the document prints the BUSINESS name, not the MyPlus vertical brand', () => {
    cy.seedProduct({ name: 'Head_' + uniq(), sellingPrice: 25, stock: 10 }).then(({ productId }) =>
      customerOfType('CyHead_' + uniq(), 'RETAILER').then((cust) =>
        sell(cust, productId, 1, 25, 25).then((invoiceNo) =>
          receiptOf(invoiceNo).then((inv) => {
            expect(inv, 'the payload carries a letterhead at all').to.have.property('letterhead')
            const named = Object.assign({}, inv, {
              letterhead: Object.assign({}, inv.letterhead, { businessName: 'SHAFEEQ MEDICINE COMPANY' }),
            })
            render(named).then((html) => {
              expect(html, "the shop's own name").to.contain('SHAFEEQ MEDICINE COMPANY')
              expect(html, 'and not ours').to.not.contain('MyPlus Pharmacy')
            })
          }))))
  })

  // ── account balance presentation ──────────────────────────────────────────────────────────

  it('a credit sale to a trade account prints the balance with a DR marker', () => {
    cy.seedProduct({ name: 'Bal_' + uniq(), sellingPrice: 200, stock: 10 }).then(({ productId }) =>
      customerOfType('CyBal_' + uniq(), 'WHOLESALE').then((cust) =>
        sell(cust, productId, 2, 200, 0).then((invoiceNo) =>
          receiptOf(invoiceNo).then((inv) => {
            render(inv).then((html) => {
              expect(html, 'DR marker on the balance').to.contain('DR')
            })
          }))))
  })

  // ── amount in words ───────────────────────────────────────────────────────────────────────

  it('the trade invoice writes the total in words', () => {
    cy.window().then((win) => {
      const words = win.DocumentRenderer.amountInWords(
        4100, { currencyWord: 'Rupees' }, { numberSystem: 'indian' })
      expect(words).to.contain('Four Thousand One Hundred')
      expect(words).to.contain('Rupees')
      expect(words).to.contain('Only')
    })
  })
})
