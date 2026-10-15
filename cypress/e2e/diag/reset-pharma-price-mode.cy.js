/**
 * One-off cleanup (2026-10-11): owner.pharma was left on Per batch pricing by an interrupted pricing-spec run (the spec
 * restores "whatever it found", so the left-over stuck). Puts the tenant back on the platform default ("latest"), as
 * it was at ~19:20 that day, through the app's own reset — and checks it.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/diag/reset-pharma-price-mode.cy.js"
 */
describe('cleanup — owner.pharma back on the default pricing mode', () => {
  it('resets pos.pricing.purchaseMode to the default', () => {
    cy.loginAsPharmaOwner()
    cy.postForm('/resetBusinessConfig', { key: 'pos.pricing.purchaseMode' })
    cy.request('/getBusinessConfig').then((r) => {
      const e = ((r.body && (r.body.collection || r.body.data)) || []).find((x) => x.key === 'pos.pricing.purchaseMode')
      expect(e, 'the setting is listed').to.exist
      expect(e.isDefault, `back on the default (value ${e.value})`).to.eq(true)
    })
  })
})
