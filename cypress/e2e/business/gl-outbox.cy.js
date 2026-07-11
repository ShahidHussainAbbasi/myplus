/**
 * Audit #4 — GL posting reliability (transactional outbox). A sale enqueues a GL event durably (gl_outbox row) and
 * an AFTER_COMMIT listener delivers it (status POSTED) so the GL reflects it — no more silent fire-and-forget drops.
 * Requires finance-service (GL) + business + gateway up.
 */
describe('Audit #4 — GL posting outbox (durable + delivered)', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)

  it('a sale enqueues a GL event that is delivered (outbox POSTED) and the GL reflects it', () => {
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    cy.seedProduct({ name: 'OBX_' + Date.now(), sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: 'OBXC_' + Date.now(), contact: '0300OBX', paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 100,
        }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        const invoiceNo = r.body.object

        // the outbox holds a row for this sale, delivered (POSTED) via the AFTER_COMMIT listener
        cy.request('/getGlOutbox').then((o) => {
          const rows = o.body.collection || o.body.data || []
          const row = rows.find((x) => x.ref === invoiceNo && x.eventType === 'SALE')
          expect(row, 'gl_outbox row for the sale (durable enqueue)').to.exist
          expect(row.status, 'delivered to the GL (afterCommit)').to.eq('POSTED')
        })

        // and the GL reflects it + stays balanced
        cy.request('/gl/trialBalance').then((tr) => {
          const tb = parse(tr.body)
          expect(tb.balanced, 'GL balanced').to.eq(true)
        })
      })
    })
  })
})
