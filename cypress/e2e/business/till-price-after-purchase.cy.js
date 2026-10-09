/**
 * TILL-PRICE — after a purchase moves a product's selling price, the till (same page, no reload) offers the NEW price.
 *
 * Reported 2026-10-09 on owner.pharma@ (Desora, sale line 5823): the product price was 297.70 after two purchases
 * (Latest + Auto markup 14.5%), but the till filled — and the sale charged — 260, the price at registration.
 * The till fills the rate from the product picker's data-price (business.js loadStock); the picker is a per-page-load
 * cache (product-picker.js) invalidated only by product WRITE urls, and a purchase is not one.
 *
 * What each case protects:
 *   F1  Latest: a purchase that re-prices the product → the till's rate box shows the new price without a reload
 *
 * Tenant: owner.business@ (POS, Latest). The purchase is posted from the PAGE (its own jQuery, global, CSRF) so the
 * page's ajaxComplete hooks see it exactly as they see the purchase form's save. Saves one PRM-TP bill (spec-owned
 * product + vendor), as pricing-purchase-mode.cy.js does.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/till-price-after-purchase.cy.js --headed --browser chrome
 */
const STAMP = Date.now()
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

let vendorId = null
const ensureVendor = () => {
  if (vendorId) return cy.wrap(vendorId)
  const vname = 'PRMTP_V_' + STAMP
  return cy.ensureCompany().then((companyId) => {
    cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
      body: { name: vname, companyId, mobile: '0304' + String(STAMP).slice(-7), email: 'prmtp' + STAMP + '@t.com' } })
    return cy.request('/getUserVenders').then((vr) => {
      const html = String(vr.body && vr.body.object != null ? vr.body.object : vr.body)
      vendorId = (new RegExp('<option value=(\\d+)[^>]*>' + vname).exec(html) || [])[1]
      expect(vendorId, 'spec vendor').to.exist
      return cy.wrap(vendorId)
    })
  })
}

const pick = (productId) => {
  cy.intercept('GET', '/productStock*').as('stock')
  cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 15000 }).should('exist')
  cy.get('#sellItemDD').select(String(productId), { force: true })
  cy.wait('@stock', { timeout: 15000 })
}

describe('TILL-PRICE — the till offers the price a purchase just set', () => {
  beforeEach(() => cy.loginAsOwner())

  it('F1 Latest: after a purchase re-prices the product, the till shows the new price without a reload', () => {
    cy.request('/getBusinessConfig').then((r) => {
      const all = r.body.collection || r.body.data || r.body.object || []
      const e = all.find((x) => x.key === 'pos.pricing.purchaseMode')
      expect(e ? e.value : 'latest', 'this business is in Latest').to.eq('latest')
      const m = all.find((x) => x.key === 'pos.pricing.markupMode')
      expect(m ? m.value : 'suggest', 'no Auto markup on this business').to.not.eq('auto')
    })
    cy.seedProduct({ name: 'PRMTP_' + uniq(), sellingPrice: 200 }).then(({ productId }) => {
      ensureVendor().then((venderId) => {
        cy.visitSaleScreen()
        cy.get('#sellItemDD option', { timeout: 30000 }).should('have.length.greaterThan', 1)
        pick(productId)
        cy.get('#sellSellRate').should(($i) => expect(Number($i.val()), 'before: the registration price').to.eq(200))

        // The purchase, from the page — as the purchase form's save reaches the server and the page's hooks.
        cy.window().then({ timeout: 30000 }, (w) => new Cypress.Promise((resolve, reject) => {
          w.$.ajax({ type: 'POST', url: w.serverContext + 'addPurchase', headers: w.xsrfHeaders ? w.xsrfHeaders() : {},
            data: { productId, venderId, quantity: 2, purchaseRate: 210, 'stock.bpurchaseRate': 210, 'stock.bsellRate': 250,
                    totalAmount: 420, netAmount: 80, purchaseInvoiceNo: 'PRM-TP-' + uniq() } })
            .done((b) => resolve(b)).fail((x) => reject(new Error('addPurchase ' + x.status)))
        })).then((b) => expect(b.status, JSON.stringify(b).slice(0, 200)).to.eq('SUCCESS'))
        cy.request('/getCatalogProduct?id=' + productId).its('body.data.sellingPrice').then((p) =>
          expect(Number(p), 'the purchase moved the product price').to.eq(250))

        // Same page, no reload: pick another line, then the product again.
        cy.get('#sellItemDD').select('', { force: true })
        pick(productId)
        cy.get(`#sellItemDD option[value="${productId}"]`).should(($o) =>
          expect(Number($o.attr('data-price')), 'the picker offers the new price').to.eq(250))
        cy.get('#sellSellRate').should(($i) => expect(Number($i.val()), 'the till fills the new price').to.eq(250))
      })
    })
  })
})
