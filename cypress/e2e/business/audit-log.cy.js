/**
 * Audit #6 — immutable audit trail (standalone audit-service). Money/stock events are captured in business-service's
 * transactional outbox and delivered to audit-service; the dashboard reads them via /getAuditLog. Append-only: a void
 * adds a VOID_SALE row and never rewrites the original SALE. Requires audit-service + business + gateway up. Headed.
 */
describe('Audit #6 — immutable audit trail', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  // Tolerate a non-JSON body (e.g. audit-service/gateway still warming up returns an HTML page) → treat as empty so
  // findAudit retries rather than crashing. A persistent HTML body means the read chain (monolith /getAuditLog →
  // gateway /api/audit → audit-service) isn't fully deployed.
  const rows = (b) => {
    if (b == null) return []
    if (typeof b !== 'string') return b
    try { return JSON.parse(b) } catch (e) { return [] }
  }
  // Delivery is async (AFTER_COMMIT + relay), so poll the trail until the expected row shows up.
  const findAudit = (pred, attempt = 0) =>
    cy.request('/getAuditLog?limit=200').then((r) => {
      const hit = rows(r.body).find(pred)
      if (hit) return hit
      if (attempt >= 45) throw new Error('audit row not found after retries')   // ~35s: cover a relay tick if LB was cold
      cy.wait(750)
      return findAudit(pred, attempt + 1)
    })

  it('a sale, its void, and a receipt each append an immutable audit entry', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    cy.seedProduct({ name: 'AUDP_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      const custName = 'AUDC_' + Date.now()
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: custName, contact: '0300AUD', paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 100,
        }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        const invoiceNo = r.body.object

        // 1) SALE audited: right ref, amount, actor, source.
        findAudit((x) => x.action === 'SALE' && x.entityRef === invoiceNo).then((sale) => {
          expect(Number(sale.amount), 'sale amount').to.be.greaterThan(0)
          expect(sale.userId, 'actor stamped').to.not.be.null
          expect(sale.sourceService, 'source service').to.eq('business')
          const saleId = sale.id

          // 2) Void it → a VOID_SALE row appears; the SALE row is unchanged (append-only).
          cy.request({ method: 'POST', url: '/voidSell', form: true, body: { invoiceNo, reason: 'CY audit' }, failOnStatusCode: false })
            .then((v) => expect(v.body.status, JSON.stringify(v.body)).to.eq('SUCCESS'))
          findAudit((x) => x.action === 'VOID_SALE' && x.entityRef === invoiceNo).then(() => {
            cy.request('/getAuditLog?limit=200').then((r2) => {
              const original = rows(r2.body).find((x) => x.id === saleId)
              expect(original, 'original SALE row still present').to.exist
              expect(original.action, 'SALE row not rewritten').to.eq('SALE')
            })
          })
        })
      })

      // 3) A receipt on a fresh credit sale → RECEIPT audited.
      const rcName = 'AUDR_' + Date.now()
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: rcName, contact: '0300AUR', paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 100,
        }, failOnStatusCode: false,
      }).then((r) => expect(r.body.status).to.eq('SUCCESS'))
      cy.request('/getUserCustomer?q=-1').then((cr) => {
        const c = (cr.body.collection || cr.body.data || []).find((x) => x.name === rcName)
        const due = Number(c.dueAmount || 0)
        cy.request({ method: 'POST', url: '/receivePayment', form: true, body: { customerId: c.customerId || c.id, amount: due, method: 'CASH' }, failOnStatusCode: false })
          .then((p) => expect(p.body.status).to.eq('SUCCESS'))
        findAudit((x) => x.action === 'RECEIPT' && Number(x.amount) === due).then((rcpt) => {
          expect(rcpt.entityType).to.eq('CUSTOMER')
        })
      })
    })
  })
})
