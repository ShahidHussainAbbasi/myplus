/**
 * Product screen — Customer-parity refactor. The Product master now renders through the shared loadDataTable()
 * DataTable path (like Customer): /getUserProduct returns {status,collection}; the form supports add + edit(update);
 * Delete deactivates (drops off the active list); per-row Add-stock (addstkbtn_<id>) is preserved. Run headed.
 */
describe('Product screen — Customer parity (list/add/edit/deactivate + add-stock)', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('getUserProduct returns a {status,collection} list with the seeded product (active only)', () => {
    cy.seedProduct({ name: 'PU_' + Date.now(), sellingPrice: 12, taxRate: 5 }).then(({ productId, name }) => {
      cy.request('/getUserProduct?q=-1').then((r) => {
        expect(r.body.status).to.eq('SUCCESS')
        const mine = (r.body.collection || []).find((p) => p.id === productId)
        expect(mine, 'seeded product in collection').to.exist
        expect(mine.name).to.eq(name)
        expect(mine).to.have.property('userId')   // loadDataTable bookkeeping
      })
    })
  })

  it('updateProduct edits the master; getCatalogProduct reflects it', () => {
    cy.seedProduct({ name: 'PE_' + Date.now(), sellingPrice: 10 }).then(({ productId, name, sku }) => {
      cy.request({
        method: 'POST', url: '/updateProduct', headers: { 'Content-Type': 'application/json' },
        body: { id: productId, name: name + '_edited', sku, sellingPrice: 33, taxRate: 0, unit: 'box', categoryName: 'General' },
        failOnStatusCode: false,
      }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

      cy.request('/getCatalogProduct?id=' + productId).then((r) => {
        expect(r.body.data.name).to.eq(name + '_edited')
        expect(Number(r.body.data.sellingPrice)).to.eq(33)
      })
    })
  })

  it('deactivateProduct removes the product from the active list', () => {
    cy.seedProduct({ name: 'PD_' + Date.now() }).then(({ productId }) => {
      cy.request({
        method: 'POST', url: '/deactivateProduct', headers: { 'Content-Type': 'application/json' },
        body: { checked: String(productId) }, failOnStatusCode: false,
      }).then((r) => expect(r.body.success).to.eq(true))

      cy.request('/getUserProduct?q=-1').then((r) => {
        const still = (r.body.collection || []).find((p) => p.id === productId)
        expect(still, 'deactivated product is gone from the active list').to.not.exist
      })
    })
  })

  it('renders #tableProduct as a DataTable with the product row + add-stock control', () => {
    cy.seedProduct({ name: 'PT_' + Date.now(), stock: 7 }).then(({ productId }) => {
      cy.visit('/businessDashboard')
      cy.window().then((w) => w.showProducts())
      cy.get('#ProductDiv').should('be.visible')
      // DataTable render: the shared path builds the toolbar (length select) + the seeded row's add-stock button.
      cy.get('select[name="tableProduct_length"]', { timeout: 10000 }).should('exist')
      cy.get('#addstkbtn_' + productId, { timeout: 10000 }).should('exist')
      cy.get('#stk_' + productId).should('exist')
    })
  })
})
