/**
 * M3c.1 (slice 76) — historical product_id backfill. The admin endpoint is tenant-scoped + idempotent; it stamps
 * product_id onto legacy Stock-linked sells/purchases and reports coverage. Run headed.
 */
describe('M3c.1 — historical product_id backfill (slice 76)', () => {
  beforeEach(() => cy.loginAsBusiness())   // needs ADD_ITEM; testIsolation clears the session

  it('runs, returns coverage counts, and is idempotent', () => {
    cy.request({ method: 'POST', url: '/backfillProductIds', headers: { 'Content-Type': 'application/json' } }).then((r) => {
      expect(r.body, JSON.stringify(r.body)).to.have.property('sellsRemaining')
      expect(Number(r.body.sellsRemaining), 'sells still without product_id').to.be.gte(0)
      expect(Number(r.body.purchasesRemaining), 'purchases still without product_id').to.be.gte(0)
    })
    // a second run backfills nothing new (idempotent)
    cy.request({ method: 'POST', url: '/backfillProductIds', headers: { 'Content-Type': 'application/json' } }).then((r) => {
      expect(Number(r.body.sellsBackfilled), 'no new sells on re-run').to.eq(0)
      expect(Number(r.body.purchasesBackfilled), 'no new purchases on re-run').to.eq(0)
    })
  })
})
