/**
 * Barcode-first sell. A product carries a scannable barcode (distinct from its sku); a scan resolves the product by
 * barcode OR sku via /lookupProduct, and a sale built from the resolved productId goes through. An unknown code
 * resolves to nothing. Requires catalog + business + gateway up. Run headed.
 */
describe('Barcode-first sell', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const parse = (b) => (typeof b === 'string' ? (b ? JSON.parse(b) : {}) : b)
  const lookup = (code) => cy.request({ url: `/lookupProduct?code=${encodeURIComponent(code)}`, failOnStatusCode: false }).then((r) => parse(r.body))

  it('resolves a product by barcode and by sku; unknown code is a miss; the sale posts', () => {
    const stamp = Date.now()
    const barcode = 'BAR' + stamp
    const sku = 'SKU' + stamp
    cy.seedProduct({ name: 'ScanP_' + stamp, sku, barcode, sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      // Scan the barcode → resolves this product.
      lookup(barcode).then((ref) => {
        expect(ref.id, 'barcode resolves the product ' + JSON.stringify(ref)).to.eq(productId)
        expect(Number(ref.sellingPrice), 'carries the price').to.eq(100)
      })
      // The sku also resolves (shops that use sku = barcode).
      lookup(sku).then((ref) => expect(ref.id, 'sku resolves the same product').to.eq(productId))
      // An unknown code is a clean miss (no id) — the scan UI shows "not found".
      lookup('NOPE' + stamp).then((ref) => expect(ref.id == null, 'unknown code returns no product').to.eq(true))

      // A sale built from the scanned productId (as scanAddToCart would) posts fine.
      cy.request({
        method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
        body: {
          customer: { name: 'ScanC_' + stamp, contact: '0300SC', paidAmount: 0, dueAmount: 0 },
          sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
          paidAmount: 0, dueAmount: 0, grandTotal: 100,
        }, failOnStatusCode: false,
      }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    })
  })
})
