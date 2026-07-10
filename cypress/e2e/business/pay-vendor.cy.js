/**
 * F1 — Pay Vendor (AP subledger). An on-credit purchase (paid < net) creates a vendor payable; /payVendor
 * FIFO-allocates a payment across the vendor's open purchase bills, recomputes the vendor due, and records a
 * DISBURSEMENT in the shared finance-service ledger (voucher PV-######). Mirror of receive-payment.cy.js.
 * Requires finance-service + business-service + gateway up. Run headed.
 */
describe('F1 — Pay Vendor settles a vendor payable (AP subledger)', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const vendorDueById = (venderId) =>
    cy.request('/getUserVender').then((r) => {
      const v = (r.body.collection || r.body.data || []).find((x) => x.id === venderId)
      return v ? Number(v.dueAmount || 0) : 0
    })

  it('on-credit purchase creates a payable; payVendor settles it and returns a PV- voucher', () => {
    const stamp = Date.now()
    // company + vendor (addVender needs a companyId)
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'PVCo_' + stamp, email: `pvco${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const company = (cr.body.collection || cr.body.data || []).find((c) => c.name === 'PVCo_' + stamp)
      expect(company, 'seeded company').to.exist
      const companyId = company.id
      const vName = 'PVVEN_' + stamp
      cy.request({ method: 'POST', url: '/addVender', form: true,
        body: { name: vName, companyId, mobile: '03001112222', email: `pv${stamp}@t.com` } })
        .then((vr) => expect(vr.body.status, JSON.stringify(vr.body)).to.eq('SUCCESS'))

      cy.request('/getUserVender').then((lr) => {
        const vendor = (lr.body.collection || lr.body.data || []).find((x) => x.name === vName)
        expect(vendor, 'seeded vendor').to.exist
        const venderId = vendor.id

        cy.seedProduct({ name: 'PVP_' + stamp, sellingPrice: 100, stock: 10 }).then(({ productId }) => {
          // on-credit purchase: net 100, paid 0 -> the vendor is owed 100
          cy.request({
            method: 'POST', url: '/addPurchase', form: true,
            body: {
              productId, quantity: 10, venderId, paidAmount: 0,
              'stock.bpurchaseRate': 10, 'stock.bsellRate': 12,
              totalAmount: 100, netAmount: 100, purchaseInvoiceNo: 'PVINV-' + stamp,
            }, failOnStatusCode: false,
          }).then((pr) => expect(pr.body.status, JSON.stringify(pr.body)).to.eq('SUCCESS'))

          vendorDueById(venderId).then((d) => expect(d, 'credit purchase created a payable').to.eq(100))

          // pay half -> due 50; voucher is a PV- number from the ledger
          cy.request({ method: 'POST', url: '/payVendor', form: true, body: { venderId, amount: 50, method: 'CASH' }, failOnStatusCode: false })
            .then((r1) => {
              expect(r1.body.status, JSON.stringify(r1.body)).to.eq('SUCCESS')
              expect(r1.body.object, 'payment result').to.exist
              expect(String(r1.body.object.voucherNo), 'AP voucher PV-######').to.match(/^PV-/)
            })
          vendorDueById(venderId).then((d) => expect(d, 'payable halved').to.eq(50))

          // pay the rest -> due 0
          cy.request({ method: 'POST', url: '/payVendor', form: true, body: { venderId, amount: 50, method: 'CASH' }, failOnStatusCode: false })
            .then((r2) => expect(r2.body.status).to.eq('SUCCESS'))
          vendorDueById(venderId).then((d) => expect(d, 'payable fully settled').to.eq(0))
        })
      })
    })
  })
})
