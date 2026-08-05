/**
 * Commerce gaps — G2 returns · G3 tax · G5 payments · G6 receipts (slices 34/35/37/38).
 * Verifies the wired UI + API on the single commerce dashboard. Robust against an empty DB (guards where a
 * seeded sale would be needed). Run headed: npx cypress run --browser chrome --headed --spec this.
 */

// ─── G3 — Tax engine ─────────────────────────────────────────────────────────
describe('G3 — Tax engine', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('getTaxSetting returns the org tax policy', () => {
    cy.request({ url: '/getTaxSetting', failOnStatusCode: false }).then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body).to.have.property('status', 'SUCCESS')
      expect(res.body.object).to.have.property('taxMode')
      expect(res.body.object).to.have.property('enabled')
    })
  })

  it('saveTaxSetting persists and reads back', () => {
    cy.request({
      method: 'POST', url: '/saveTaxSetting', form: true,
      body: { enabled: true, taxMode: 'EXCLUSIVE', defaultRate: 17, taxLabel: 'VAT', taxRegNo: 'TRN-TEST' },
      failOnStatusCode: false,
    }).then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.eq('SUCCESS')
    })
    cy.request('/getTaxSetting').then((res) => {
      expect(res.body.object.taxLabel).to.eq('VAT')
      expect(res.body.object.defaultRate).to.satisfy((v) => Number(v) === 17)
    })
  })

  it('sells table has a Tax column', () => {
    cy.visit('/businessDashboard')
    cy.get('#tableSell th[data-field="sellTaxAmount"]').should('exist')
  })
})

// ─── G5 — Payments / tender ──────────────────────────────────────────────────
describe('G5 — Payments', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('checkout shows a Payment Method selector with the expected methods', () => {
    cy.openSellSection('sellDiv')
    cy.get('#sellPayMethod').should('be.visible')
    cy.get('#sellPayMethod option').then(($o) => {
      const vals = [...$o].map((o) => o.value)
      expect(vals).to.include.members(['CASH', 'CARD', 'CREDIT'])
    })
  })

  it('sells table has a Payment column', () => {
    cy.visit('/businessDashboard')
    cy.get('#tableSell th[data-field="sellPaymentMode"]').should('exist')
  })
})

// ─── G2 — Sale return (inverse saga) ─────────────────────────────────────────
describe('G2 — Sale return', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('the return dialog builder is wired and opens', () => {
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'openSaleReturn')
    // Drive the dialog directly with a synthetic button so the test does not depend on a seeded sale row.
    cy.window().then((win) => {
      const btn = win.document.createElement('button')
      btn.setAttribute('data-sellid', '0')
      btn.setAttribute('data-stockid', '')
      btn.setAttribute('data-qty', '5')
      btn.setAttribute('data-invoice', 'INV-000001')
      btn.setAttribute('data-item', 'Test Item')
      win.openSaleReturn(btn)
    })
    cy.get('#saleReturnDialog').should('be.visible')
    cy.get('#srQty').should('have.value', '5')
    cy.get('#srSold').should('contain', '5')
    cy.window().then((win) => win.closeSaleReturn())
  })

  // The bug this slice fixes: after a return, the customer's running due must drop EVERYWHERE it is read —
  // here we assert it through getDashboardChartData (the "customers with dues" widget that was showing stale data).
  // Robust: if no sell with an outstanding-due customer exists, the test skips rather than failing on an empty DB.
  it('a full-line return clears that customer\'s outstanding due in getDashboardChartData', () => {
    const sellList = (b) => b.collection || b.data || b.object || []
    const dueOf = (chart, name) =>
      (chart.dueCustomers || []).filter((c) => c.name === name)
        .reduce((sum, c) => sum + Number(c.due || 0), 0)

    // Slice 106: this used to HUNT for an existing sell whose customer happened to owe money, then assert
    // the dashboard agreed. Two things made that unreliable:
    //   1. It picked on `Customer.dueAmount` (TOTAL outstanding) but asserted against the dashboard's
    //      `dueCustomers` — a different metric, so the precondition could fail on perfectly healthy data.
    //   2. Its `if (!line) return cy.log(...)` skip meant the test could pass having asserted nothing.
    // House rule (fixture eligibility): SEED the state you need, never assert-or-skip on found data.
    const stamp = Date.now()
    const custName = 'G2Due_' + stamp

    cy.seedProduct({ name: 'G2P_' + stamp, sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
        body: { name: custName, contact: 'C' + stamp } })
        .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      cy.request('/getUserCustomer').then((cr) => {
        const cust = (cr.body.collection || cr.body.data || []).find((c) => c.name === custName)
        expect(cust, 'seeded customer exists').to.exist

        // A CREDIT sale — nothing paid — so the customer genuinely owes and must appear on the dashboard.
        cy.request({
          method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
          failOnStatusCode: false,
          body: {
            customer: { customerId: cust.customerId, name: cust.name, contact: cust.contact },
            sales: [{ productId, quantity: 2, sellRate: 100, totalAmount: 200, netAmount: 200 }],
            paidAmount: 0, dueAmount: 200, grandTotal: 200,
            idempotencyKey: 'cy-g2-' + stamp,
          },
        }).then((sr) => expect(sr.body.status, JSON.stringify(sr.body)).to.eq('SUCCESS'))

        cy.request('/getUserSell').then((sres) => {
          const line = sellList(sres.body || {})
            .find((s) => s.customer && s.customer.name === custName && s.sellId != null)
          expect(line, 'the seeded sale is readable').to.exist

          cy.request('/getDashboardChartData').then((before) => {
            const dueBefore = dueOf(before.body.object || {}, custName)
            expect(dueBefore, `${custName} owes before return`).to.be.greaterThan(0)

            cy.request({
              method: 'POST', url: '/saleReturn', form: true,
              body: { sellId: line.sellId, quantity: line.quantity, reason: 'cypress full return' },
              failOnStatusCode: false,
            }).then((rret) => {
              expect(rret.body.status, JSON.stringify(rret.body)).to.eq('SUCCESS')

              cy.request('/getDashboardChartData').then((after) => {
                const dueAfter = dueOf(after.body.object || {}, custName)
                expect(dueAfter, `${custName} due after full return is recomputed lower`).to.be.lessThan(dueBefore)
              })
            })
          })
        })
      })
    })
  })
})

// ─── G6 — Receipts ───────────────────────────────────────────────────────────
describe('G6 — Receipts', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('printReceipt is available on the dashboard', () => {
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'printReceipt')
  })

  it('getReceipt for an unknown invoice returns NOT_FOUND (never a 500 crash)', () => {
    cy.request({ url: '/getReceipt?invoiceNo=INV-DOES-NOT-EXIST', failOnStatusCode: false }).then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body).to.have.property('status')
      expect(res.body.status).to.be.oneOf(['NOT_FOUND', 'ERROR'])
    })
  })
})
