/**
 * E-commerce (slice 60) — storefront product name search. The public browse accepts an optional q (case-insensitive
 * name contains); the store page has a search box that filters the grid. Run headed.
 */
describe('E-commerce — storefront product search', () => {
  let orgId
  const tag = Date.now()
  const apple = 'Apple_' + tag
  const banana = 'Banana_' + tag

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => { orgId = ((r.body.collection || [])[0] || {}).id })
    cy.request({ method: 'POST', url: '/addProduct', body: { name: apple, sku: 'A' + tag, sellingPrice: 5, taxRate: 0, unit: 'pcs' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
    cy.request({ method: 'POST', url: '/addProduct', body: { name: banana, sku: 'B' + tag, sellingPrice: 6, taxRate: 0, unit: 'pcs' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
  })

  it('the public browse filters by name with q', () => {
    cy.request('/storefront/products?org=' + orgId + '&q=' + encodeURIComponent(apple)).then((r) => {
      const names = (r.body.data || []).map((p) => p.name)
      expect(names, JSON.stringify(names)).to.include(apple)
      expect(names).to.not.include(banana)
    })
    // empty q returns both
    cy.request('/storefront/products?org=' + orgId).then((r) => {
      const names = (r.body.data || []).map((p) => p.name)
      expect(names).to.include.members([apple, banana])
    })
  })

  it('the store page search box filters the grid', () => {
    cy.visit('/store?org=' + orgId)
    cy.contains('.card .name', apple, { timeout: 10000 }).should('exist')
    cy.get('#storeSearch').clear().type('Banana_' + tag)
    cy.contains('.card .name', banana, { timeout: 10000 }).should('exist')
    cy.get('#grid').should('not.contain', apple)
  })
})
