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
    // #sellPayMethod is a bootstrap-select: the plugin hides the real <select> (display:none) and
    // renders a button in its place, so :visible on the <select> can never be true. Judge what the
    // cashier actually sees — the wrapper — and read the options off the <select> behind it.
    cy.get('#sellPayMethod').should('exist')
    cy.get('#sellPayMethod').next('.bootstrap-select').should('be.visible')
    cy.get('#sellPayMethod option').then(($o) => {
      const vals = $o.toArray().map((o) => o.value)   // .toArray(): jQuery is array-LIKE, not iterable
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
    let owed = 0

    // Work out what it takes to be rank 1, BEFORE seeding — because the figure has to go on the PRODUCT.
    //
    // `dueCustomers` is the TOP TEN by outstanding due, so the seeded customer has to out-owe the current
    // leader or `dueOf` reads 0 and the precondition fails on perfectly healthy data.
    //
    // The previous attempt computed the same figure but put it on the SALE LINE (`sellRate: owed`). That
    // silently did nothing: the sale path is authoritative on price and re-quotes every line from the
    // catalog and the price rules — a client cannot name its own price, which is the whole point of
    // B2B-P2 ("the contract price is the price charged"). So the invoice was raised at the product's real
    // value, the customer owed a couple of hundred rather than thousands, fell below the top-ten cut-off,
    // and `dueBefore` read 0 while the debt genuinely existed. Verified in the data: the customer from the
    // failing run holds due_amount 234.00 against a top-ten floor of 300.
    //
    // Price the PRODUCT at that figure instead and the server's own arithmetic produces the debt.
    cy.request('/getDashboardChartData').then((seedRead) => {
      const seedRows = (seedRead.body.object || {}).dueCustomers || []
      owed = Math.ceil(seedRows.reduce((m, c) => Math.max(m, Number(c.due || 0)), 0)) + 5000
    })

    cy.then(() => cy.seedProduct({ name: 'G2P_' + stamp, sellingPrice: owed, stock: 5 })).then(({ productId }) => {
      cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
        body: { name: custName, contact: 'C' + stamp } })
        .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      cy.request('/getUserCustomer').then((cr) => {
        const cust = (cr.body.collection || cr.body.data || []).find((c) => c.name === custName)
        expect(cust, 'seeded customer exists').to.exist

        // A CREDIT sale — nothing paid — so the customer genuinely owes and tops the dashboard list.
        // ONE unit (not N × rate) keeps the full-line return below the seeded stock of 5; the value comes
        // from the product's price, which is why that is where `owed` was applied.
        //
        // The amounts are still sent because the endpoint's DTO carries them, but they are the SERVER's
        // to decide — the assertion below reads the debt back from the dashboard rather than trusting
        // anything echoed here.
        cy.request({
          method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
          failOnStatusCode: false,
          body: {
            customer: { customerId: cust.customerId, name: cust.name, contact: cust.contact },
            sales: [{ productId, quantity: 1, sellRate: owed, totalAmount: owed, netAmount: owed }],
            paidAmount: 0, dueAmount: owed, grandTotal: owed,
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
