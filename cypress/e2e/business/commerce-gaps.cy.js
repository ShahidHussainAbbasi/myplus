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
