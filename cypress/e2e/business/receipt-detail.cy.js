/**
 * B2B Phase 3b-2 (#4) — richer receipt: line numbers, batch/expiry per line, and the account balance.
 *
 * THE POINT OF THIS SLICE is not the receipt — it is that `StockReservationResponse.picks` has always told
 * business-service exactly which batches a FEFO reservation consumed (its own javadoc says it exists "so the
 * sale ... records exact batch traceability") and nothing ever consumed it. Every sale knew which batches
 * left the shelf and discarded it. With 3a capturing batch IN and this recording batch OUT, a recall is
 * answerable in both directions.
 *
 * Design: microservices/docs/slices/b2b-P3-documents-reports.md
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/receipt-detail.cy.js --headed --no-exit --config screenshotOnRunFailure=false
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => {
  for (const key of ['collection', 'data', 'object']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return Array.isArray(body) ? body : []
}

const receiptOf = (invoiceNo) =>
  cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo))
    .then((r) => {
      expect(r.body.status, `getReceipt ${invoiceNo}`).to.eq('SUCCESS')
      return cy.wrap(r.body.object)
    })

const customer = (name) =>
  cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
    body: { name, contact: 'C' + uniq() } })
    .then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      return cy.request('/getUserCustomer')
    })
    .then((r) => {
      const c = list(r.body).find((x) => x.name === name)
      expect(c, 'customer created').to.exist
      return cy.wrap(c)
    })

/** Sell `qty` at `rate`, paying `paid`. Returns the invoice number. */
const sell = (cust, productId, qty, rate, paid) =>
  cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: {
      customer: { customerId: cust.customerId, name: cust.name, contact: cust.contact },
      sales: [{ productId, quantity: qty, sellRate: rate, totalAmount: qty * rate, netAmount: qty * rate }],
      tenders: paid > 0 ? [{ method: 'CASH', amount: paid }] : [],
      paidAmount: paid, grandTotal: qty * rate,
      idempotencyKey: 'cy-rcpt-' + uniq(),
    },
  }).then((r) => {
    expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
    return cy.wrap(r.body.object)
  })

describe('B2B P3b-2 — receipt detail (#4)', () => {

  beforeEach(() => { cy.loginAsOwner() })

  // ── batch traceability: the data that was being thrown away ──────────────────

  it('a sale RECORDS the batch it drew from — the reservation picks are no longer discarded', () => {
    const batch = 'RB-' + uniq()
    cy.seedProduct({ name: 'Trace_' + uniq(), sellingPrice: 50, stock: 20, batchNo: batch })
      .then(({ productId }) => {
        customer('Trace_' + uniq()).then((c) => {
          sell(c, productId, 2, 50, 100).then((invoiceNo) => {
            receiptOf(invoiceNo).then((inv) => {
              const line = (inv.sales || [])[0]
              expect(line, 'the invoice has a line').to.exist
              expect(line.batches, 'the line carries its batches').to.be.an('array')
              expect(line.batches.length,
                'FEFO picked a batch and the sale recorded it — this is the whole slice')
                .to.be.greaterThan(0)
              const nos = line.batches.map((b) => b.batchNo)
              expect(nos, 'the batch that was actually on the shelf').to.include(batch)
            })
          })
        })
      })
  })

  it('the recorded batch carries its quantity, so a partial recall is answerable', () => {
    const batch = 'RQ-' + uniq()
    cy.seedProduct({ name: 'TraceQ_' + uniq(), sellingPrice: 10, stock: 30, batchNo: batch })
      .then(({ productId }) => {
        customer('TraceQ_' + uniq()).then((c) => {
          sell(c, productId, 3, 10, 30).then((invoiceNo) => {
            receiptOf(invoiceNo).then((inv) => {
              const batches = ((inv.sales || [])[0] || {}).batches || []
              expect(batches.length).to.be.greaterThan(0)
              const total = batches.reduce((sum, b) => sum + (Number(b.quantity) || 0), 0)
              expect(total, 'the batch quantities account for the whole line').to.be.closeTo(3, 0.001)
            })
          })
        })
      })
  })

  it('a product with no batch still sells, and simply has no batch rows', () => {
    // A hardware shop has no lot numbers. The receipt must not grow an empty column for them.
    cy.seedProduct({ name: 'NoTrace_' + uniq(), sellingPrice: 25 }).then(({ productId }) => {
      cy.request({ method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' },
        body: { productId, quantity: 5 }, failOnStatusCode: false })
      customer('NoTrace_' + uniq()).then((c) => {
        sell(c, productId, 1, 25, 25).then((invoiceNo) => {
          receiptOf(invoiceNo).then((inv) => {
            const line = (inv.sales || [])[0]
            expect(line, 'the sale still went through').to.exist
            const batches = line.batches || []
            batches.forEach((b) => {
              expect(Boolean(b.batchNo || b.expiryDate), 'no meaningless empty batch rows').to.eq(true)
            })
          })
        })
      })
    })
  })

  // ── the account balance snapshot ─────────────────────────────────────────────

  describe('balance snapshot', () => {

    it('a credit sale records what the customer owed AFTER it', () => {
      cy.seedProduct({ name: 'Bal_' + uniq(), sellingPrice: 200, stock: 10 }).then(({ productId }) => {
        customer('Bal_' + uniq()).then((c) => {
          sell(c, productId, 1, 200, 0).then((invoiceNo) => {
            receiptOf(invoiceNo).then((inv) => {
              expect(inv.balanceAfter, 'the snapshot exists').to.not.be.null
              expect(Number(inv.balanceAfter), 'a new customer owing 200 after one credit sale')
                .to.be.closeTo(200, 0.01)
            })
          })
        })
      })
    })

    it('the snapshot is per-invoice, so a LATER sale does not rewrite an earlier receipt', () => {
      // The reason this is snapshotted rather than read from Customer.dueAmount at print time: the current
      // balance would put today's figure on a reprint of an old invoice.
      cy.seedProduct({ name: 'Bal2_' + uniq(), sellingPrice: 100, stock: 10 }).then(({ productId }) => {
        customer('Bal2_' + uniq()).then((c) => {
          sell(c, productId, 1, 100, 0).then((first) => {
            sell(c, productId, 1, 100, 0).then(() => {
              receiptOf(first).then((inv) => {
                expect(Number(inv.balanceAfter),
                  'the FIRST invoice still shows the balance as it stood then, not 200')
                  .to.be.closeTo(100, 0.01)
              })
            })
          })
        })
      })
    })

    it('a fully paid walk-in sale needs no balance lines', () => {
      cy.seedProduct({ name: 'Bal3_' + uniq(), sellingPrice: 30, stock: 10 }).then(({ productId }) => {
        customer('Bal3_' + uniq()).then((c) => {
          sell(c, productId, 1, 30, 30).then((invoiceNo) => {
            receiptOf(invoiceNo).then((inv) => {
              // balanceAfter may be 0 — what matters is the receipt does not claim a debt.
              const owed = (inv.dueAmount != null && Number(inv.dueAmount) < 0)
                ? -Number(inv.dueAmount) : 0
              expect(owed, 'nothing owed on a paid sale').to.eq(0)
              if (inv.balanceAfter != null) {
                expect(Number(inv.balanceAfter), 'and no balance carried').to.be.closeTo(0, 0.01)
              }
            })
          })
        })
      })
    })
  })

  // ── rendering ────────────────────────────────────────────────────────────────

  it('receipt.js is loaded and its new labels resolve in the active language', () => {
    cy.visit('/businessDashboard')
    cy.window().then((w) => {
      expect(typeof w.printReceipt, 'receipt.js loaded').to.eq('function')
      ;['ui.js.batchShort', 'ui.js.expShort', 'ui.js.previousBalance', 'ui.js.newBalance'].forEach((k) => {
        expect(w.t(k), `${k} resolves (ui.js.* is the only prefix shipped to the browser)`).to.not.eq(k)
      })
    })
  })
})
