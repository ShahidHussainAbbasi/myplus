/**
 * Slice 53 — Product-master sync. Registering a catalog Product (the single master) auto-projects a bridged
 * business Item, so the ONE registration surfaces in the POS itemId picker AND sells through the shared saga +
 * inventory (the same path POS/storefront/pharmacy use). Run headed.
 */
describe('Item→Product master-sync', () => {
  let productId, itemId
  const pname = 'MasterProd_' + Date.now()

  beforeEach(() => cy.loginAsBusiness())   // POS vertical

  it('registering a Product projects a bridged Item into the POS item list', () => {
    cy.request({ method: 'POST', url: '/addProduct', body: { name: pname, sku: 'MP' + Date.now(), sellingPrice: 40, taxRate: 0, unit: 'pcs', categoryName: 'General' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        productId = r.body.data.id
      })

    cy.request('/getUserItem').then((r) => {
      const items = r.body.collection || r.body.object || r.body.data || []
      const mine = items.find((i) => i.iname === pname)
      expect(mine, 'the Product was projected into a POS Item').to.exist
      itemId = mine.id
    })
  })

  it('the projected item sells through the shared saga and decrements inventory', () => {
    // stock the master in inventory, then sell it via the POS saga using the projected itemId.
    cy.request({ method: 'POST', url: '/addProductStock', body: { productId, quantity: 10 }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
    cy.request('/productStock?productId=' + productId).then((r) => expect(parseFloat(r.body.stock)).to.eq(10))

    cy.request({
      method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
      body: {
        customer: { name: 'SyncCust_' + Date.now(), contact: '0300SYN', paidAmount: 40, dueAmount: 0 },
        sales: [{ itemId, quantity: 1, sellRate: 40, totalAmount: 40, netAmount: 40 }],
      },
      failOnStatusCode: false,
    }).then((r) => {
      expect(r.body.status, JSON.stringify(r.body).substring(0, 200)).to.eq('SUCCESS')
    })

    // the saga (itemId→productId→inventory) decremented the master's on-hand
    const poll = (tries) => cy.request('/productStock?productId=' + productId).then((r) => {
      const s = parseFloat(r.body.stock)
      if (s <= 9 || tries <= 0) expect(s, 'saga decremented the master on-hand').to.eq(9)
      else { cy.wait(800); poll(tries - 1) }
    })
    poll(6)
  })
})
