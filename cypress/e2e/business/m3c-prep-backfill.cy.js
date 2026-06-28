/**
 * M3c.4 PRECONDITION — map every Item to a catalog Product, then backfill product_id onto historical Stock-linked
 * sells/purchases, and assert NOTHING remains. Once this is green, the local-Stock fallbacks are safe to remove
 * (M3c.4). Per-tenant: run it logged in as each org that has legacy data. Run headed.
 */
describe('M3c prep — migrate-catalog + backfill to zero', () => {
  it('maps all items + backfills product_id; *Remaining == 0', () => {
    cy.loginAsBusiness()

    cy.request({ method: 'POST', url: '/migrateCatalog', headers: { 'Content-Type': 'application/json' } })
      .then((r) => cy.log('migrate-catalog: ' + JSON.stringify(r.body)))

    cy.request({ method: 'POST', url: '/backfillProductIds', headers: { 'Content-Type': 'application/json' } })
      .then((r) => {
        cy.log('backfill: ' + JSON.stringify(r.body))
        expect(Number(r.body.sellsRemaining), 'sells still without product_id').to.eq(0)
        expect(Number(r.body.purchasesRemaining), 'purchases still without product_id').to.eq(0)
      })
  })
})
