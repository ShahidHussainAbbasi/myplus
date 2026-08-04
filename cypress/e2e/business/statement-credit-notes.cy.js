/**
 * B2B Phase 3f — credit notes on statements, and the end of retro-edited invoices.
 *
 * THE DEFECT: a return re-settled the invoice header in place, and the statement read that header. So an
 * invoice issued at 500 with a 200 return showed as a single BILL of 300 — the running balance was right,
 * but the customer's copy said 500, ours said 300, and the credit note that explains the gap appeared
 * NOWHERE. 3c made those notes real documents; this makes them visible where they are reconciled.
 *
 * THE PROMISE THIS GATE ENFORCES: the trail changes, the money does NOT. Every balance assertion below is
 * the SAME number the old netted statement produced. If one moves, 3f has broken something it swore not to
 * touch — which is why the closing balance and Customer.dueAmount are pinned as hard as the new lines are.
 *
 * NOT COVERED HERE: a pre-V34 credit note (NULL credit_amount, excluded by the repository so its invoice
 * keeps its back-filled netted value). Cypress cannot seed a NULL-value return — no endpoint produces one
 * after V34 — so that path is covered by StatementTrailTest#valuelessNoteIsInert plus the repository's
 * `credit_amount is not null` clause, not by this gate. Stated rather than faked.
 *
 * Design: microservices/docs/slices/b2b-P3f-credit-notes-on-statements.md
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/statement-credit-notes.cy.js --headed --no-exit --config screenshotOnRunFailure=false
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const num = (v) => Number(v || 0)

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

/** A CREDIT sale (nothing paid) — the case where refundAmount is zero, so only credit_amount can carry the note's value. */
const creditSale = (cust, productId, qty, rate) =>
  cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      customer: { customerId: cust.customerId, name: cust.name, contact: cust.contact },
      sales: [{ productId, quantity: qty, sellRate: rate, totalAmount: qty * rate, netAmount: qty * rate }],
      paidAmount: 0, dueAmount: qty * rate, grandTotal: qty * rate,
      idempotencyKey: 'cy-3f-' + uniq(),
    },
  }).then((r) => {
    expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
    return cy.wrap(r.body.object)
  })

const firstSellIdOf = (invoiceNo) =>
  cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo)).then((r) => {
    expect(r.body.object, `receipt for ${invoiceNo}: ${JSON.stringify(r.body)}`).to.exist
    const line = (r.body.object.sales || [])[0]
    expect(line, 'invoice has a line to return').to.exist
    return cy.wrap(line.sellId)
  })

const statement = (customerId) =>
  cy.request('/customerStatement?customerId=' + customerId).then((r) => {
    expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
    return cy.wrap(list(r.body))
  })

describe('B2B P3f — the statement shows the document trail', () => {

  beforeEach(() => { cy.loginAsOwner() })   // VOID_INVOICE is owner-gated; testIsolation clears the session

  // ── AR: the invoice stops moving, the credit note appears ────────────────────

  it('an issued invoice appears at its issued value', () => {
    cy.seedProduct({ name: 'P3fA_' + uniq(), sellingPrice: 100, stock: 10 }).then(({ productId }) => {
      customer('P3fA_' + uniq()).then((c) => {
        creditSale(c, productId, 5, 100).then((invoiceNo) => {
          statement(c.customerId).then((lines) => {
            const bill = lines.find((l) => l.docNo === invoiceNo && l.type === 'BILL')
            expect(bill, `a BILL line for ${invoiceNo}: ${JSON.stringify(lines)}`).to.exist
            expect(num(bill.debit), 'billed at what was issued').to.be.closeTo(500, 0.01)
            expect(num(lines[lines.length - 1].balance), 'owes the full invoice').to.be.closeTo(500, 0.01)
          })
        })
      })
    })
  })

  it('after a return the invoice STILL reads 500 and a credit note explains the 200', () => {
    // This is the defect, in one test: before 3f the BILL line silently became 300 and no CRN- line existed.
    cy.seedProduct({ name: 'P3fB_' + uniq(), sellingPrice: 100, stock: 10 }).then(({ productId }) => {
      customer('P3fB_' + uniq()).then((c) => {
        creditSale(c, productId, 5, 100).then((invoiceNo) => {
          firstSellIdOf(invoiceNo).then((sellId) => {
            cy.request({ method: 'POST', url: '/saleReturn', form: true, failOnStatusCode: false,
              body: { sellId, quantity: 2, reason: 'damaged' } })
              .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

            statement(c.customerId).then((lines) => {
              const bill = lines.find((l) => l.docNo === invoiceNo && l.type === 'BILL')
              expect(bill, `the invoice is still on the statement: ${JSON.stringify(lines)}`).to.exist
              expect(num(bill.debit), 'the ISSUED invoice was not rewritten by the return')
                .to.be.closeTo(500, 0.01)

              const cn = lines.find((l) => l.type === 'CREDIT_NOTE')
              expect(cn, `a CREDIT_NOTE line: ${JSON.stringify(lines)}`).to.exist
              expect(String(cn.docNo), 'it is the credit note document, not the invoice').to.match(/^CRN-\d+/)
              expect(num(cn.credit), 'for the value of the goods returned').to.be.closeTo(200, 0.01)
            })
          })
        })
      })
    })
  })

  it('the balance is UNCHANGED — 3f moves documents, never money', () => {
    // The regression that matters most: a wrong sign or a double-count here would misstate every ledger.
    cy.seedProduct({ name: 'P3fC_' + uniq(), sellingPrice: 100, stock: 10 }).then(({ productId }) => {
      customer('P3fC_' + uniq()).then((c) => {
        creditSale(c, productId, 5, 100).then((invoiceNo) => {
          firstSellIdOf(invoiceNo).then((sellId) => {
            cy.request({ method: 'POST', url: '/saleReturn', form: true, failOnStatusCode: false,
              body: { sellId, quantity: 2, reason: 'damaged' } })
              .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

            statement(c.customerId).then((lines) => {
              expect(num(lines[lines.length - 1].balance), 'closing balance == 500 - 200, exactly as before 3f')
                .to.be.closeTo(300, 0.01)
            })

            // ...and the customer's running due — read by dues, aging and the dashboard — did not move either.
            cy.request('/getUserCustomer').then((r) => {
              const me = list(r.body).find((x) => x.customerId === c.customerId)
              expect(me, 'customer still readable').to.exist
              expect(num(me.dueAmount), 'Customer.dueAmount untouched by the trail change')
                .to.be.closeTo(300, 0.01)
            })
          })
        })
      })
    })
  })

  it('the CSV mirrors the screen, credit note included', () => {
    // 3d's guarantee: the file a customer reconciles against comes from the SAME service method as the screen.
    cy.seedProduct({ name: 'P3fD_' + uniq(), sellingPrice: 100, stock: 10 }).then(({ productId }) => {
      customer('P3fD_' + uniq()).then((c) => {
        creditSale(c, productId, 5, 100).then((invoiceNo) => {
          firstSellIdOf(invoiceNo).then((sellId) => {
            cy.request({ method: 'POST', url: '/saleReturn', form: true, failOnStatusCode: false,
              body: { sellId, quantity: 2, reason: 'damaged' } })
              .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

            statement(c.customerId).then((lines) => {
              cy.request('/customerStatement.csv?customerId=' + c.customerId).then((csv) => {
                const body = String(csv.body)
                expect(body, 'the credit note reached the file').to.contain('CREDIT_NOTE')
                expect(body, 'named by its own document number').to.contain(
                  String(lines.find((l) => l.type === 'CREDIT_NOTE').docNo))
                // One header row + one row per statement line — the file cannot quietly drop or invent a line.
                const rows = body.trim().split(/\r?\n/).filter((x) => x.length)
                expect(rows.length, `CSV rows == ${lines.length} lines + header`).to.eq(lines.length + 1)
              })
            })
          })
        })
      })
    })
  })

  it('a voided invoice nets to zero, not to its full value', () => {
    // The trap this design had to dodge: voidSell ZEROES the header, so an issued value with nothing
    // offsetting it would have overstated every voided invoice by its full amount.
    cy.seedProduct({ name: 'P3fE_' + uniq(), sellingPrice: 100, stock: 10 }).then(({ productId }) => {
      customer('P3fE_' + uniq()).then((c) => {
        creditSale(c, productId, 3, 100).then((invoiceNo) => {
          cy.request({ method: 'POST', url: '/voidSell', form: true, failOnStatusCode: false,
            body: { invoiceNo, reason: 'CY 3f void' } })
            .then((v) => expect(v.body.status, JSON.stringify(v.body)).to.eq('SUCCESS'))

          statement(c.customerId).then((lines) => {
            const mine = lines.filter((l) => l.docNo === invoiceNo)
            expect(mine.length, `the void left a trail: ${JSON.stringify(lines)}`).to.eq(2)
            expect(mine.find((l) => l.type === 'BILL'), 'the invoice that was issued').to.exist

            const voided = mine.find((l) => l.type === 'VOID')
            expect(voided, 'and its cancellation').to.exist
            expect(num(voided.credit), 'cancelling the full issued value').to.be.closeTo(300, 0.01)

            expect(num(lines[lines.length - 1].balance), 'a voided invoice owes NOTHING')
              .to.be.closeTo(0, 0.01)
          })
        })
      })
    })
  })

  // ── AP: the supplier side of the same trail ──────────────────────────────────

  it('a vendor statement shows the bill and its debit note', () => {
    const stamp = uniq()
    const invNo = 'P3F-' + stamp
    cy.request({ method: 'POST', url: '/addCompany', form: true, failOnStatusCode: false,
      body: { name: 'P3fCo_' + stamp, email: `p3fco${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const company = list(cr.body).find((x) => x.name === 'P3fCo_' + stamp)
      expect(company, 'company created').to.exist
      const vName = 'P3fVen_' + stamp
      cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
        body: { name: vName, companyId: company.id, mobile: '03007778888', email: `p3fv${stamp}@t.com` } })
        .then((vr) => expect(vr.body.status, JSON.stringify(vr.body)).to.eq('SUCCESS'))

      cy.request('/getUserVender').then((lr) => {
        const vendor = list(lr.body).find((x) => x.name === vName)
        expect(vendor, 'vendor created').to.exist
        cy.seedProduct({ name: 'P3fVP_' + stamp, sellingPrice: 120 }).then(({ productId }) => {
          cy.request({
            method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
            body: { productId, quantity: 10, venderId: vendor.id, paidAmount: 0,
                    'stock.bpurchaseRate': 100, 'stock.bsellRate': 120,
                    totalAmount: 1000, netAmount: 1000, purchaseInvoiceNo: invNo },
          }).then((pr) => expect(pr.body.status, JSON.stringify(pr.body)).to.eq('SUCCESS'))

          cy.request('/getUserPurchase').then((r) => {
            const p = list(r.body).find((x) => x.purchaseInvoiceNo === invNo)
            expect(p, 'the bill exists to return against').to.exist

            cy.request({ method: 'POST', url: '/purchaseReturn', form: true, failOnStatusCode: false,
              body: { purchaseId: p.purchaseId, quantity: 2, reason: 'short-shipped' } })
              .then((rr) => expect(rr.body.status, JSON.stringify(rr.body)).to.eq('SUCCESS'))

            cy.request('/vendorStatement?venderId=' + vendor.id).then((sr) => {
              expect(sr.body.status, JSON.stringify(sr.body)).to.eq('SUCCESS')
              const lines = list(sr.body)

              const bill = lines.find((l) => l.docNo === invNo && l.type === 'BILL')
              expect(bill, `a BILL line for ${invNo}: ${JSON.stringify(lines)}`).to.exist
              expect(num(bill.debit), 'the bill as ISSUED, not net of the return').to.be.closeTo(1000, 0.01)

              const dn = lines.find((l) => l.type === 'DEBIT_NOTE')
              expect(dn, `a DEBIT_NOTE line: ${JSON.stringify(lines)}`).to.exist
              expect(String(dn.docNo), 'the debit note document').to.match(/^DBN-\d+/)
              expect(num(dn.credit), '2 of 10 units returned off a 1000 bill').to.be.closeTo(200, 0.01)

              expect(num(lines[lines.length - 1].balance), 'we still owe 800')
                .to.be.closeTo(800, 0.01)
            })
          })
        })
      })
    })
  })
})
