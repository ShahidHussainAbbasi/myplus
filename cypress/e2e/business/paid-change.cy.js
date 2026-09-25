/**
 * PAID-1 — change handed back is NOT money the shop kept.
 *
 * REPORTED: INV-000054 (org 15) — 272.00 handed over for a 42.00 bill was stored paid 272.00, due +230.00, payment
 * CASH 272.00. Traced consequences, each asserted here where it breaks:
 *   1 the invoice: paid = the bill, due = 0, tendered/change still recorded for the receipt
 *   2 the shift: expected cash rises by the BILL, not by the tender
 *   3 a VOID refunds what was kept — the old code refunded the change a second time
 *   4 a customer's real debt survives a later sale with change (recomputeDue summed +change against it)
 *
 * Server rules, so the sales are posted through /addSell exactly as the till posts them.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/paid-change.cy.js --headed --browser chrome
 */
const uniq = () => `${Date.now()}`.slice(-8) + Math.floor(Math.random() * 900 + 100)
const list = (b) => (b && (b.collection || b.object || b.data)) || []

const line = (productId, qty, rate) => ({
  productId, itemId: productId, quantity: qty, sellRate: rate, totalAmount: qty * rate,
  stock: { bsellRate: rate, bsellDiscount: '', bsellDiscountType: '0' },
})

/** One sale through /addSell, as the till sends it. Yields the invoice number. */
const sell = (customer, lines, tenders) =>
  cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: { customer, sales: lines, tenders, idempotencyKey: `cy-paid1-${uniq()}` },
  }).then((r) => {
    expect(r.body.status, `addSell: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS')
    return r.body.object
  })

const receipt = (invoiceNo) =>
  cy.request(`/getReceipt?invoiceNo=${encodeURIComponent(invoiceNo)}`).then((r) => {
    expect(r.body.status, 'getReceipt').to.eq('SUCCESS')
    return r.body.object
  })

const shift = () => cy.request('/shiftReport').then((r) => {
  expect(r.body && r.body.object, `shiftReport: ${JSON.stringify(r.body).slice(0, 160)}`).to.be.an('object')
  return r.body.object
})

describe('PAID-1 — change handed back is not money kept', () => {
  beforeEach(() => cy.loginAsOwner())
  // Leave no server state even when a case FAILS mid-way: case 2 opens a shift, and a red run once left it open.
  after(() => {
    cy.loginAsOwner()
    cy.request({ method: 'POST', url: '/closeShift', form: true, body: { countedCash: 0 }, failOnStatusCode: false })
  })

  it('⭐⭐ 1 — 150 handed over for a 100 bill: paid 100, due 0; tendered 150 and change 50 still on the receipt', () => {
    cy.seedProduct({ name: `PC1_${uniq()}`, sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      sell({ name: `Walkin_${uniq()}`, contact: '03000000001' }, [line(productId, 1, 100)],
        [{ method: 'CASH', amount: 150 }]).then((inv) => receipt(inv).then((r) => {
        expect(Number(r.grandTotal)).to.eq(100)
        expect(Number(r.paidAmount), '⭐ what the shop KEPT').to.eq(100)
        expect(Number(r.dueAmount), 'not +50').to.eq(0)
        expect(Number(r.tenderedAmount), 'still printed').to.eq(150)
        expect(Number(r.changeAmount), 'still printed').to.eq(50)
      }))
    })
  })

  it('⭐⭐ 2+3 — the shift gains the BILL, and a VOID refunds what was kept, not the change again', () => {
    // A clean shift of our own: close any open one first (a leftover from another spec).
    cy.request({ method: 'POST', url: '/closeShift', form: true, body: { countedCash: 0 }, failOnStatusCode: false })
    cy.request({ method: 'POST', url: '/openShift', form: true, body: { openingFloat: 100 } })
      .its('body.status').should('eq', 'SUCCESS')
    cy.seedProduct({ name: `PC2_${uniq()}`, sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      sell({ name: `Walkin_${uniq()}`, contact: '03000000002' }, [line(productId, 1, 100)],
        [{ method: 'CASH', amount: 150 }]).then((inv) => {
        shift().then((s) => {
          expect(Number(s.cashSales), '⭐ the drawer kept 100, not 150').to.eq(100)
          expect(Number(s.expectedCash), 'float 100 + 100 kept').to.eq(200)
        })
        cy.request({ method: 'POST', url: '/voidSell', form: true, body: { invoiceNo: inv, reason: 'CY PAID-1' } })
          .its('body.status').should('eq', 'SUCCESS')
        shift().then((s) => {
          expect(Number(s.refunds), '⭐ the void refunds the 100 kept — the old code refunded 150').to.eq(-100)
          expect(Number(s.expectedCash), 'back to the float').to.eq(100)
        })
      })
    })
    // Leave no server state: close the shift this case opened.
    cy.request({ method: 'POST', url: '/closeShift', form: true, body: { countedCash: 100 }, failOnStatusCode: false })
  })

  it('⭐⭐ 4 — a customer who owes 100 still owes 100 after a later cash sale with change', () => {
    const name = `Debtor_${uniq()}`
    const contact = `0301${String(Date.now()).slice(-7)}`
    cy.seedProduct({ name: `PC4_${uniq()}`, sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      // Sale A on account: 100 owed.
      sell({ name, contact }, [line(productId, 1, 100)], [{ method: 'CREDIT', amount: 0 }])
      cy.request('/getUserCustomer').then((r) => {
        const c = list(r.body).find((x) => x.name === name)
        expect(c, 'the debtor was created').to.exist
        expect(Number(c.dueAmount), 'owes the first sale').to.eq(100)
        // Sale B for 30, paid with 50 cash → 20 change, same customer.
        sell({ name, contact, customerId: c.customerId }, [line(productId, 1, 30)], [{ method: 'CASH', amount: 50 }])
        cy.request('/getUserCustomer').then((r2) => {
          const c2 = list(r2.body).find((x) => x.customerId === c.customerId)
          expect(Number(c2.dueAmount), '⭐ still 100 — the old code made it 80 (the change credited to the account)')
            .to.eq(100)
        })
      })
    })
  })
})
