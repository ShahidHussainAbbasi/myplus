/**
 * Audit #2 — editing a sale/purchase posts a GL adjustment (reverse the old, repost the new) so the books never
 * drift on an edit. We edit a sale UP and assert cumulative Sales RISES (the repost added more than the reversal
 * removed) and the trial balance stays balanced. Requires finance-service (GL) + business + gateway up.
 */
describe('Audit #2 — sale edit posts a GL adjustment', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
  const tb = () => cy.request('/gl/trialBalance').then((r) => parse(r.body))
  const salesCredit = (t) => Number(((t.rows || []).find((x) => x.code === '4000') || { credit: 0 }).credit)

  it('editing a credit sale from qty 1 to qty 2 raises Sales and stays balanced', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    const custName = 'GLE_' + Date.now()
    cy.seedProduct({ name: 'GLEP_' + Date.now(), sellingPrice: 100, stock: 10 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: custName, contact: '0300GLE', paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 100,
        }, failOnStatusCode: false,
      }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      tb().then((afterSale) => {
        const salesBeforeEdit = salesCredit(afterSale)
        expect(afterSale.balanced, 'balanced after sale').to.eq(true)

        cy.request('/getUserSell').then((gs) => {
          const line = (gs.body.collection || gs.body.data || []).find((s) => s.productId === productId)
          expect(line, 'sell line').to.exist
          cy.request('/getSellInvoice?sellId=' + line.sellId).then((gi) => {
            const chId = gi.body.object.customer_history_id
            // edit qty 1 -> 2 (net 200)
            cy.request({
              method: 'POST', url: '/updateSell', headers: { 'Content-Type': 'application/json' },
              body: {
                customer_history_id: chId,
                customer: { name: custName, contact: '0300GLE' },
                sales: [{ productId, quantity: 2, sellRate: 100, totalAmount: 200, netAmount: 200 }],
              }, failOnStatusCode: false,
            }).then((u) => expect(u.body.status, JSON.stringify(u.body)).to.eq('SUCCESS'))

            tb().then((afterEdit) => {
              expect(afterEdit.balanced, 'balanced after edit').to.eq(true)
              expect(Number(afterEdit.totalDebit)).to.eq(Number(afterEdit.totalCredit))
              expect(salesCredit(afterEdit), 'Sales rose after editing the sale up (GL adjusted, not stale)')
                .to.be.greaterThan(salesBeforeEdit)
            })
          })
        })
      })
    })
  })
})
