/**
 * Audit #6 — immutable audit trail (standalone audit-service). Money/stock events are captured in business-service's
 * transactional outbox and delivered to audit-service; the dashboard reads them via /getAuditLog. Append-only: a void
 * adds a VOID_SALE row and never rewrites the original SALE. Requires audit-service + business + gateway up. Headed.
 */
describe('Audit #6 — immutable audit trail', () => {
  /*
   * ⭐ THE OWNER, because the trail is OWNER-ONLY BY DESIGN.
   *
   * This ran as `cy.loginAsBusiness()` — demo.business@, which holds no ROLE_OWNER. audit-service guards
   * the read with `hasAuthority('ROLE_OWNER') or hasAuthority('ROLE_ADMIN')` and its javadoc explains why:
   * a PRIVILEGE gate would be no gate at all, since every tenant owner holds the super privilege set inside
   * their own organization. It ends "A refusal here is a real 403: a security event, reported as one."
   *
   * So the 403 was the control working. Verified end to end before changing this: the sale WAS audited
   * (org 6, INV-000456, delivered with attempts=1), the outbox said POSTED, and only the READ was refused
   * — the monolith log showed `403 Forbidden on GET /api/audit "Access denied"`.
   *
   * ⚠ Note what this means for coverage: no case here exercises a NON-owner. If a cashier or a demo user
   * should ever be able to read their own org's trail, that is a deliberate change to a security control
   * and belongs in its own slice — not something to be arrived at by relaxing a gate until a spec passes.
   */
  beforeEach(() => { cy.loginAsOwner() })

  // Tolerate a non-JSON body (e.g. audit-service/gateway still warming up returns an HTML page) → treat as empty so
  // findAudit retries rather than crashing. A persistent HTML body means the read chain (monolith /getAuditLog →
  // gateway /api/audit → audit-service) isn't fully deployed.
  const rows = (b) => {
    if (b == null) return []
    if (typeof b === 'string') {
      try { b = JSON.parse(b) } catch (e) { return [] }
    }
    /*
     * ⚠ ONLY AN ARRAY IS A LIST OF ROWS.
     *
     * This returned the body unchanged for anything non-string, so the monolith's error envelope —
     * AuditController answers `{"status":"ERROR"}` when the read chain (monolith → gateway → audit-service)
     * is unreachable — came back as an OBJECT and the caller crashed on `.find is not a function`.
     *
     * The comment above says this helper exists to tolerate exactly that and let findAudit RETRY. It could
     * not: it crashed on the first bad answer instead of polling through a service that was still warming
     * up. Anything that is not an array is "no rows yet".
     */
    return Array.isArray(b) ? b : []
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
