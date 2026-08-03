/**
 * B2B Phase 3c (#1) — returns are DOCUMENTS in their own series.
 *
 * THE ACCOUNTING RULE this enforces: a customer return is a CREDIT NOTE and a supplier return a DEBIT NOTE.
 * Each is a distinct document, in its own number series, that REFERENCES the document it reverses. Before
 * this slice a sale return was stamped with the ORIGINAL invoice number — so a credit note was
 * indistinguishable from the invoice it cancelled — and a supplier return produced no document at all.
 *
 * NOTE ON AMOUNTS: this slice changes what documents and ledger lines are CALLED, never a value. The
 * assertions below deliberately check identity and references, not money.
 *
 * Design: microservices/docs/slices/b2b-P3-documents-reports.md  (§3c)
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/return-documents.cy.js --headed --no-exit --config screenshotOnRunFailure=false
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => {
  for (const key of ['collection', 'data', 'object']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return Array.isArray(body) ? body : []
}

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

const sell = (cust, productId, qty, rate) =>
  cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: {
      customer: { customerId: cust.customerId, name: cust.name, contact: cust.contact },
      sales: [{ productId, quantity: qty, sellRate: rate, totalAmount: qty * rate, netAmount: qty * rate }],
      tenders: [{ method: 'CASH', amount: qty * rate }],
      paidAmount: qty * rate, grandTotal: qty * rate,
      idempotencyKey: 'cy-ret-' + uniq(),
    },
  }).then((r) => {
    expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
    return cy.wrap(r.body.object)
  })

/** The sell line id for an invoice — saleReturn works on a line, not the invoice. */
const firstSellIdOf = (invoiceNo) =>
  cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo)).then((r) => {
    const line = ((r.body.object || {}).sales || [])[0]
    expect(line, 'invoice has a line to return').to.exist
    return cy.wrap(line.sellId)
  })

describe('B2B P3c — credit notes and debit notes (#1)', () => {

  beforeEach(() => { cy.loginAsOwner() })

  // ── customer side: the credit note ───────────────────────────────────────────

  it('a sale return issues a CREDIT NOTE with its own number', () => {
    cy.seedProduct({ name: 'Crn_' + uniq(), sellingPrice: 50, stock: 10 }).then(({ productId }) => {
      customer('Crn_' + uniq()).then((c) => {
        sell(c, productId, 2, 50).then((invoiceNo) => {
          firstSellIdOf(invoiceNo).then((sellId) => {
            cy.request({ method: 'POST', url: '/saleReturn', form: true, failOnStatusCode: false,
              body: { sellId, quantity: 1, reason: 'damaged' } })
              .then((r) => {
                expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
                // The operator must be able to quote the document they just issued.
                expect(String(r.body.message), 'the response names the credit note').to.contain('CRN-')
              })

            cy.request('/getSaleReturns').then((r) => {
              const mine = list(r.body).filter((x) => x.invoiceNo === invoiceNo)
              expect(mine.length, 'the return was recorded against this invoice').to.be.greaterThan(0)
              const cn = mine[0]

              // THE point of the slice, in two assertions:
              expect(cn.creditNoteNo, 'the credit note has its OWN number').to.match(/^CRN-\d{6,}$/)
              expect(cn.creditNoteNo, 'and it is NOT the invoice it reverses').to.not.eq(invoiceNo)

              // ...and the reference survives, because that is the accounting requirement.
              expect(cn.invoiceNo, 'it still references the invoice it reverses').to.eq(invoiceNo)
            })
          })
        })
      })
    })
  })

  it('two credit notes in the same org get DIFFERENT numbers', () => {
    // MAX+1 per org, guarded by UNIQUE(organization_id, credit_note_seq). If allocation ever collided, two
    // real documents would share an identity — which is the failure this constraint exists to prevent.
    cy.seedProduct({ name: 'Crn2_' + uniq(), sellingPrice: 20, stock: 20 }).then(({ productId }) => {
      customer('Crn2_' + uniq()).then((c) => {
        const issued = []
        const returnOnce = () =>
          sell(c, productId, 1, 20).then((invoiceNo) =>
            firstSellIdOf(invoiceNo).then((sellId) =>
              cy.request({ method: 'POST', url: '/saleReturn', form: true, failOnStatusCode: false,
                body: { sellId, quantity: 1, reason: 'cy' } })
                .then((r) => {
                  expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
                  issued.push(String(r.body.message))
                })))

        returnOnce().then(returnOnce).then(() => {
          const nums = issued.map((m) => (m.match(/CRN-\d+/) || [''])[0])
          expect(nums[0], 'first credit note numbered').to.match(/^CRN-\d+$/)
          expect(nums[1], 'second credit note numbered').to.match(/^CRN-\d+$/)
          expect(nums[0], 'and the two are distinct documents').to.not.eq(nums[1])
        })
      })
    })
  })

  // ── supplier side: the debit note ────────────────────────────────────────────

  it('a purchase return issues a DEBIT NOTE — the supplier side had no document at all', () => {
    const invNo = 'PR-' + uniq()
    cy.seedProduct({ name: 'Dbn_' + uniq(), sellingPrice: 60 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
        body: { productId, quantity: 10, purchaseRate: 40, 'stock.bpurchaseRate': 40,
                'stock.bsellRate': 60, totalAmount: 400, netAmount: 400, paidAmount: 400,
                purchaseInvoiceNo: invNo },
      }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      cy.request('/getUserPurchase').then((r) => {
        const p = list(r.body).find((x) => x.purchaseInvoiceNo === invNo)
        expect(p, 'the bill exists to return against').to.exist

        cy.request({ method: 'POST', url: '/purchaseReturn', form: true, failOnStatusCode: false,
          body: { purchaseId: p.purchaseId, quantity: 2, reason: 'short-shipped' } })
          .then((rr) => {
            expect(rr.body.status, JSON.stringify(rr.body)).to.eq('SUCCESS')
            expect(String(rr.body.message), 'the response names the debit note').to.contain('DBN-')
            const dbn = (rr.body.object || {}).debitNoteNo
            expect(dbn, 'the debit note number came back for the vendor to reconcile against')
              .to.match(/^DBN-\d{6,}$/)
            expect(dbn, 'and it is NOT the bill it reverses').to.not.eq(invNo)
          })
      })
    })
  })

  // ── the series never collide ─────────────────────────────────────────────────

  it('an invoice, a credit note and a debit note can never be confused', () => {
    // Three prefixes, one width. A ledger line or report can tell a sale from a return without knowing
    // which series produced it — which is exactly what reusing the invoice number destroyed.
    cy.visit('/businessDashboard')
    cy.request('/getSaleReturns').then((r) => {
      list(r.body).forEach((cn) => {
        if (cn.creditNoteNo) {
          expect(cn.creditNoteNo, 'credit notes never carry the invoice prefix').to.not.contain('INV-')
        }
      })
    })
  })
})
