/**
 * F3c — financial statements. A sale flows into P&L income, and the Balance Sheet obeys the accounting equation
 * (Assets = Liabilities + Equity, incl. current net income). Requires finance-service (GL) + business + gateway up.
 */
describe('F3c — P&L + Balance Sheet', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)

  it('a credit sale shows up in P&L income and a balanced Balance Sheet', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    cy.seedProduct({ name: 'F3C_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: 'F3CC_' + Date.now(), contact: '0300F3C', paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 100,
        }, failOnStatusCode: false,
      }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      // P&L — the sale credited Sales (income)
      cy.request('/gl/pnl').then((r) => {
        const p = parse(r.body)
        expect(Number(p.totalIncome), 'income > 0 after a sale').to.be.greaterThan(0)
        expect(p, 'netProfit present').to.have.property('netProfit')
      })

      // Balance Sheet — accounting equation holds
      cy.request('/gl/balanceSheet').then((r) => {
        const b = parse(r.body)
        expect(b.balanced, 'balance sheet balances (Assets = Liab + Equity)').to.eq(true)
        expect(Number(b.totalAssets), 'Assets = Liabilities + Equity').to.be.closeTo(Number(b.totalLiabilities) + Number(b.totalEquity), 0.01)
        expect(Number(b.totalAssets), 'has assets (AR from the credit sale)').to.be.greaterThan(0)
      })
    })
  })
})
