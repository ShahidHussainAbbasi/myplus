/**
 * M3a (stock → inventory-only) — the purchase screen's "pick an existing batch" pre-fill now sources on-hand qty +
 * last purchase rate from INVENTORY and the sell rate from the catalog MASTER (not the legacy local Stock row).
 * We register a product (master, sell price 15), purchase a known batch @ rate 10, then assert getStockByBatch
 * reflects inventory + master. Run headed.
 */
describe('M3a — purchase batch pre-fill from inventory + master', () => {
  let itemId
  const tag = Date.now()
  const name = 'M3AProd_' + tag
  const batch = 'M3ABATCH' + tag

  beforeEach(() => cy.loginAsBusiness())

  it('getStockByBatch returns inventory on-hand + last purchase rate + master sell price', () => {
    // 1) register via the catalog MASTER (sell price 15) → auto-projects a bridged Item
    cy.request({ method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name, sku: 'M3A' + tag, sellingPrice: 15, taxRate: 0, unit: 'pcs' } })
    cy.request('/getUserItem').then((r) => {
      const items = r.body.collection || r.body.object || r.body.data || []
      itemId = (items.find((i) => i.iname === name) || {}).id
      expect(itemId, 'bridged item created from the product master').to.exist
    })

    // 2) purchase 6 of a known batch @ purchase rate 10 → inventory StockEntry(batch, qty 6, purchasePrice 10)
    cy.then(() => {
      cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
        body: { itemId, quantity: 6, 'stock.batchNo': batch, 'stock.bpurchaseRate': 10, 'stock.bsellRate': 15,
          totalAmount: 60, netAmount: 60, purchaseInvoiceNo: 'M3A-' + tag } })
        .then((r) => expect(r.body.status, JSON.stringify(r.body).substring(0, 200)).to.eq('SUCCESS'))
    })

    // 3) getStockByBatch sources everything from inventory + master (poll for the synchronous stock-in to settle)
    cy.then(() => {
      const poll = (t) => cy.request('/getStockByBatch?batchNo=' + batch + '&itemId=' + itemId).then((r) => {
        const onHand = Number(r.body.stock)
        if (onHand === 6 || t <= 0) {
          expect(onHand, 'on-hand from inventory').to.eq(6)
          expect(Number(r.body.bpurchaseRate), 'last purchase rate from the inventory batch').to.eq(10)
          expect(Number(r.body.bsellRate), 'sell rate from the catalog master').to.eq(15)
          return
        }
        cy.wait(800); poll(t - 1)
      })
      poll(8)
    })
  })
})
