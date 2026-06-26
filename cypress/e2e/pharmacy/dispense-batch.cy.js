/**
 * Pharmacy P10 (slice 54) — batch + expiry shown on the dispense screen. A stocked lot's FEFO batch/expiry is
 * exposed via getStock and rendered when the medicine is selected, so the pharmacist sees the lot being dispensed.
 * Builds on master-sync (slice 53): registering a Product projects the Item the dispense screen uses. Run headed.
 */
describe('Pharmacy — batch/expiry on dispense (P10)', () => {
  let productId, itemId
  const pname = 'BatchMed_' + Date.now()
  const batch = 'B' + Date.now()
  const expiry = '2030-09-30'

  beforeEach(() => cy.loginAsPharma())

  it('a stocked lot exposes its FEFO batch + expiry via getStock', () => {
    cy.request({ method: 'POST', url: '/addProduct', body: { name: pname, sku: 'BM' + Date.now(), sellingPrice: 15, taxRate: 0, unit: 'tablet' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => { expect(r.body.success, JSON.stringify(r.body)).to.eq(true); productId = r.body.data.id })

    cy.request('/getUserItem').then((r) => {
      const items = r.body.collection || r.body.object || r.body.data || []
      const mine = items.find((i) => i.iname === pname)
      expect(mine, 'projected Item exists (master-sync)').to.exist
      itemId = mine.id
    })

    cy.then(() => {
      cy.request({ method: 'POST', url: '/addProductStock', body: { productId, quantity: 50, batchNo: batch, expiryDate: expiry }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      cy.request('/getStock?itemId=' + itemId).then((r) => {
        const batches = r.body.batches || []
        expect(batches.length, 'getStock returns FEFO batches').to.be.greaterThan(0)
        expect(batches[0].batchNo).to.eq(batch)
        expect(batches[0].expiryDate).to.eq(expiry)
        expect(r.body.bexpDate, 'first-batch expiry surfaced on the DTO').to.eq(expiry)
      })
    })
  })

  it('the dispense screen shows the FEFO batch/expiry when the medicine is selected', () => {
    cy.visit('/businessDashboard')
    cy.get('#sellType', { timeout: 10000 }).select('sellDiv', { force: true })   // open the Sell/dispense screen
    cy.get('#sellDiv').should('be.visible')
    cy.window().then((w) => { w.tableV = 'Sell'; w.loadStock(pname, itemId) })   // simulate picking the medicine
    cy.get('#sellBatchInfo', { timeout: 10000 }).should('be.visible')
      .and('contain', batch).and('contain', expiry)
  })
})
