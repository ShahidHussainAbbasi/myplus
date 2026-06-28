/**
 * M3b (slice 75) — the purchase is self-describing: it records its own batch/rate (no local Stock row) and still
 * reaches inventory (authoritative on-hand). The purchase list renders from the Purchase's own fields. Run headed.
 */
describe('M3b — purchase self-describing, no local Stock row', () => {
  let itemId, productId
  const tag = Date.now()
  const name = 'M3bProd_' + tag
  const batch = 'M3BBATCH' + tag

  beforeEach(() => cy.loginAsBusiness())

  it('records batch/rate on the purchase + stocks inventory; the list shows it', () => {
    // register via the master → bridged item
    cy.request({ method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name, sku: 'M3B' + tag, sellingPrice: 15, taxRate: 0, unit: 'pcs' } })
      .then((r) => { productId = r.body && r.body.data && r.body.data.id })
    cy.request('/getUserItem').then((r) => {
      const items = r.body.collection || r.body.object || r.body.data || []
      itemId = (items.find((i) => i.iname === name) || {}).id
      expect(productId, 'catalog product').to.exist
    })

    // purchase 7 of a known batch @ rate 10 (form binds nested stock.*) — productId-native (M4e.2)
    cy.then(() => cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
      body: { productId, quantity: 7, 'stock.batchNo': batch, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 15,
        totalAmount: 70, netAmount: 70, purchaseInvoiceNo: 'M3B-' + tag } })
      .then((r) => expect(r.body.status, JSON.stringify(r.body).substring(0, 200)).to.eq('SUCCESS')))

    // the purchase list renders it from the Purchase's own fields (no Stock row)
    cy.then(() => cy.request('/getUserPurchase').then((r) => {
      const list = r.body.object || r.body.collection || r.body.data || []
      const mine = list.find((p) => p.iname === name)
      expect(mine, 'purchase appears in the list (not skipped)').to.exist
      expect(mine.stock && mine.stock.batchNo, 'batch carried on the purchase').to.eq(batch)
      expect(Number(mine.stock.bpurchaseRate), 'purchase rate carried').to.eq(10)
    }))

    // inventory on-hand reflects the purchase (authoritative)
    cy.then(() => {
      const poll = (t) => cy.request('/getStock?itemId=' + itemId).then((r) => {
        const s = Number((r.body || {}).stock)
        if (s === 7 || t <= 0) { expect(s, 'inventory on-hand after purchase').to.eq(7); return }
        cy.wait(700); poll(t - 1)
      })
      poll(8)
    })
  })
})
