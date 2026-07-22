/**
 * Dedicated VOID_INVOICE privilege. Voiding a sale/purchase is gated by the fine-grained VOID_INVOICE authority
 * (business-service @PreAuthorize), not the coarse ADMIN_PRIVILEGE. A holder (owner/admin/super/demo) can still void
 * (no regression); a plain cashier (ROLE_BUSINESS_USER, no VOID_INVOICE) cannot. Requires finance + business + gateway
 * up and the seeded cashier account. Run headed.
 *
 * The negative case is proven behaviourally (not by the exact HTTP status): the monolith proxy collapses the
 * downstream 403 to {status:"ERROR"}, so we assert the cashier's void does NOT succeed AND that the owner can still
 * void the very same invoice afterwards — which is only possible if the cashier's attempt changed nothing.
 */
describe('VOID_INVOICE privilege', () => {
  const sell = () =>
    cy.seedProduct({ name: 'VIP_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId }) =>
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: 'VIPC_' + Date.now(), contact: '0300VIP', paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 100,
        }, failOnStatusCode: false,
      }))

  it('a cashier cannot void; an owner still can (no regression)', () => {
    // Owner (has VOID_INVOICE via superSet) records a sale.
    cy.loginAsBusiness()
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    sell().then((r) => {
      expect(r.body.status, 'sale recorded ' + JSON.stringify(r.body)).to.eq('SUCCESS')
      const invoiceNo = r.body.object

      // Cashier (ROLE_BUSINESS_USER — no VOID_INVOICE) is denied: the void does NOT succeed.
      cy.loginAsCashierA()
      cy.request({ method: 'POST', url: '/voidSell', form: true, body: { invoiceNo, reason: 'cashier try' }, failOnStatusCode: false })
        .then((denied) => expect(denied.body.status, 'cashier void blocked ' + JSON.stringify(denied.body)).to.not.eq('SUCCESS'))

      // Owner voids the SAME invoice → SUCCESS. (Would be "already voided" if the cashier had actually voided it.)
      cy.loginAsBusiness()
      cy.request({ method: 'POST', url: '/voidSell', form: true, body: { invoiceNo, reason: 'owner void' }, failOnStatusCode: false })
        .then((v) => expect(v.body.status, 'owner void succeeds ' + JSON.stringify(v.body)).to.eq('SUCCESS'))
    })
  })
})
