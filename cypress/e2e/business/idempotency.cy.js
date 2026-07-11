/**
 * Audit #5 — Idempotency on money operations. The same client key on a double-submit / retry must NOT double-charge,
 * double-pay, or double-stock: the op runs once and the replay returns the same result. Requires finance-service (GL)
 * + business + gateway up. Run headed.
 */
describe('Audit #5 — money-op idempotency', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
  const tb = () => cy.request('/gl/trialBalance').then((r) => parse(r.body))
  const acct = (rows, code) => (rows || []).find((x) => x.code === code) || { debit: 0, credit: 0 }
  const key = () => 'idem-' + Date.now() + '-' + Math.random().toString(16).slice(2)

  it('receivePayment twice with the same key allocates once (no over-credit)', () => {
    const custName = 'IDMC_' + Date.now()
    cy.seedProduct({ name: 'IDMP_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: custName, contact: '0300IDM', paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 100,
        }, failOnStatusCode: false,
      }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      cy.request('/getUserCustomer?q=-1').then((cr) => {
        const c = (cr.body.collection || cr.body.data || []).find((x) => x.name === custName)
        const customerId = c.customerId || c.id
        const due = Number(c.dueAmount || 0)   // the actual owed amount (server applies tax authoritatively → may be > 100)
        const k = key()
        const pay = () => cy.request({ method: 'POST', url: '/receivePayment', form: true,
          body: { customerId, amount: due, method: 'CASH', idempotencyKey: k }, failOnStatusCode: false })

        pay().then((p1) => {
          expect(p1.body.status).to.eq('SUCCESS')
          const receipt1 = (p1.body.object || {}).receiptNo
          pay().then((p2) => {
            expect(p2.body.status).to.eq('SUCCESS')
            expect((p2.body.object || {}).receiptNo, 'replay returns the same receipt').to.eq(receipt1)
            // allocated once → the customer is settled, not over-credited
            cy.request('/getUserCustomer?q=-1').then((cr2) => {
              const c2 = (cr2.body.collection || cr2.body.data || []).find((x) => x.name === custName)
              expect(Number(c2.dueAmount || 0), 'due settled once (no double credit)').to.eq(0)
            })
          })
        })
      })
    })
  })

  it('addPurchase twice with the same key records one bill (stock/GL move once)', () => {
    const stamp = Date.now()
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'IDCo_' + stamp, email: `idco${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const companyId = (cr.body.collection || cr.body.data || []).find((c) => c.name === 'IDCo_' + stamp).id
      cy.request({ method: 'POST', url: '/addVender', form: true, body: { name: 'IDVEN_' + stamp, companyId, mobile: '03007778888', email: `idv${stamp}@t.com` } })
      cy.request('/getUserVender').then((lr) => {
        const venderId = (lr.body.collection || lr.body.data || []).find((x) => x.name === 'IDVEN_' + stamp).id
        cy.seedProduct({ name: 'IDPP_' + stamp, sellingPrice: 100, stock: 0 }).then(({ productId }) => {
          const inv = 'IDPINV-' + stamp
          const k = key()
          const buy = () => cy.request({
            method: 'POST', url: '/addPurchase', form: true,
            body: { productId, quantity: 10, venderId, paidAmount: 0, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 12, totalAmount: 100, netAmount: 100, purchaseInvoiceNo: inv, idempotencyKey: k },
            failOnStatusCode: false,
          })
          tb().then((before) => {
            const invBefore = Number(acct(before.rows, '1200').debit)
            buy().then((b1) => expect(b1.body.status, JSON.stringify(b1.body)).to.eq('SUCCESS'))
            buy().then((b2) => expect(b2.body.status).to.eq('SUCCESS'))   // replay, not a second bill
            // exactly one purchase row for this invoice
            cy.request('/getUserPurchase').then((pl) => {
              const rows = (pl.body.collection || pl.body.data || []).filter((x) => x.purchaseInvoiceNo === inv)
              expect(rows.length, 'one bill despite two submits').to.eq(1)
            })
            // Inventory moved by ONE bill (100), not two
            tb().then((after) => {
              expect(after.balanced, 'GL balanced').to.eq(true)
              expect(Number(acct(after.rows, '1200').debit) - invBefore, 'inventory posted once').to.eq(100)
            })
          })
        })
      })
    })
  })

  it('payVendor twice with the same key pays once', () => {
    const stamp = Date.now()
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'IPVCo_' + stamp, email: `ipvco${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const companyId = (cr.body.collection || cr.body.data || []).find((c) => c.name === 'IPVCo_' + stamp).id
      cy.request({ method: 'POST', url: '/addVender', form: true, body: { name: 'IPVEN_' + stamp, companyId, mobile: '03009990000', email: `ipv${stamp}@t.com` } })
      cy.request('/getUserVender').then((lr) => {
        const venderId = (lr.body.collection || lr.body.data || []).find((x) => x.name === 'IPVEN_' + stamp).id
        cy.seedProduct({ name: 'IPVP_' + stamp, sellingPrice: 100, stock: 0 }).then(({ productId }) => {
          // a credit purchase → the vendor is owed 100
          cy.request({
            method: 'POST', url: '/addPurchase', form: true,
            body: { productId, quantity: 10, venderId, paidAmount: 0, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 12, totalAmount: 100, netAmount: 100, purchaseInvoiceNo: 'IPVINV-' + stamp },
            failOnStatusCode: false,
          }).then((pr) => expect(pr.body.status, JSON.stringify(pr.body)).to.eq('SUCCESS'))

          const k = key()
          const pay = () => cy.request({ method: 'POST', url: '/payVendor', form: true,
            body: { venderId, amount: 100, method: 'CASH', idempotencyKey: k }, failOnStatusCode: false })
          pay().then((p1) => {
            expect(p1.body.status).to.eq('SUCCESS')
            const v1 = (p1.body.object || {}).voucherNo
            pay().then((p2) => {
              expect(p2.body.status).to.eq('SUCCESS')
              expect((p2.body.object || {}).voucherNo, 'replay returns the same voucher').to.eq(v1)
              cy.request('/getUserVender').then((vr) => {
                const v = (vr.body.collection || vr.body.data || []).find((x) => x.id === venderId)
                expect(Number(v.dueAmount || 0), 'vendor paid once (payable cleared, not negative)').to.eq(0)
              })
            })
          })
        })
      })
    })
  })
})
