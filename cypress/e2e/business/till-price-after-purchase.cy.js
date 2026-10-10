/**
 * TP-1 — after a purchase or an approval moves a product's selling price, the screens offer the NEW price without a reload.
 *
 * Reported 2026-10-09 on owner.pharma@ (Desora, sale line 5823): the product price was 297.70 after two purchases
 * (Latest + Auto markup 14.5%), but the till filled — and the sale charged — 260, the price at registration.
 * Design: microservices/docs/selling-price-per-purchase-analysis.md §12.5–§12.6.
 *
 * The till and the purchase form fill the rate from the product picker's data-price; the picker is a per-page cache
 * (product-picker.js) that only product-write URLs dropped. Two layers, one case each:
 *   F1  layer B — the till, SAME section, no reopen: the pick's /productStock answer (the live catalog price) corrects
 *       the option and the rate box
 *   F2  layer A — Purchase → save → Sale: the Sale picker already carries the new price, before any pick (the shared
 *       cache, filled on the Purchase screen at 200, was dropped by the purchase save)
 *   F3  layer A — an APPROVED price (Approval mode, approved on Purchase → Price approvals; that post is global:false,
 *       so the approval screen drops the cache itself): the Sale picker carries the approved price before any pick
 *
 * Tenant: owner.business@ (POS, Latest). The purchases in F1/F2 are posted from the PAGE (its own jQuery, global, CSRF)
 * so the page's ajaxComplete hooks see them exactly as they see the purchase form's save. Spec-owned products, vendor
 * and PRM-TP bills, as pricing-purchase-mode.cy.js does. Server state: the pricing settings F3 touches are put back
 * EXACTLY in after().
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/till-price-after-purchase.cy.js --headed --browser chrome
 */
const KEYS = ['pos.pricing.purchaseMode', 'pos.pricing.markupMode', 'pos.pricing.markupPct']
const STAMP = Date.now()
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return [] }
const set = (key, value) => cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value: String(value) }, failOnStatusCode: false })
  .then((r) => expect(r.body && r.body.success, `save ${key}=${value}: ${JSON.stringify(r.body)}`).to.eq(true))
const reset = (key) => cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key }, failOnStatusCode: false })
const priceOf = (id) => cy.request('/getCatalogProduct?id=' + id).its('body.data.sellingPrice').then(Number)

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

/** A purchase line from the PAGE: cost 210, S/U 250 — the request the purchase form's save sends. */
const purchaseFromPage = (productId, venderId) =>
  cy.window().then({ timeout: 30000 }, (w) => new Cypress.Promise((resolve, reject) => {
    w.$.ajax({ type: 'POST', url: w.serverContext + 'addPurchase', headers: w.xsrfHeaders ? w.xsrfHeaders() : {},
      data: { productId, venderId, quantity: 2, purchaseRate: 210, 'stock.bpurchaseRate': 210, 'stock.bsellRate': 250,
              totalAmount: 420, netAmount: 80, purchaseInvoiceNo: 'PRM-TP-' + uniq() } })
      .done((b) => resolve(b)).fail((x) => reject(new Error('addPurchase ' + x.status)))
  })).then((b) => expect(b.status, JSON.stringify(b).slice(0, 200)).to.eq('SUCCESS'))

/**
 * --env EVAL_SRC=1: before the monolith is rebuilt the browser has the old, content-hashed JS (cy.intercept cannot
 * replace it). Load the changed code into the page — narrowly, so no handler is bound twice:
 *   product-picker.js whole (a fresh ProductPicker; its extra ajaxComplete hook only drops a cache);
 *   business.js: loadStock ONLY (cut out by braces; called by name);
 *   price-approvals.js after unbinding its approve/reject clicks (else one click would post twice).
 */
const loadFix = () => {
  if (!Cypress.env('EVAL_SRC')) return
  const js = 'src/main/resources/static/js/'
  cy.readFile(js + 'common/product-picker.js').then((src) => cy.window().then((w) => w.eval(src)))
  cy.readFile(js + 'business/business.js').then((s) => {
    const i = s.indexOf('function loadStock(')
    let d = 0, j = s.indexOf('{', i)
    for (; j < s.length; j++) { if (s[j] === '{') d++; else if (s[j] === '}' && --d === 0) break }
    cy.window().then((w) => w.eval(s.slice(i, j + 1)))
  })
  cy.readFile(js + 'business/price-approvals.js').then((src) => cy.window().then((w) => {
    w.$(w.document).off('click', '#tablePriceApprovals .pa-approve').off('click', '#tablePriceApprovals .pa-reject')
      .off('click', '#PriceApprovalsDiv [data-pa-status]')
    w.eval(src)
  }))
}

const optionPrice = (productId, expected, why) =>
  cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 20000 }).should(($o) =>
    expect(Number($o.attr('data-price')), why).to.eq(expected))

const pick = (productId) => {
  cy.intercept('GET', '/productStock*').as('stock')
  cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 15000 }).should('exist')
  cy.get('#sellItemDD').select(String(productId), { force: true })
  cy.wait('@stock', { timeout: 15000 })
}

/** As the sidebar does it: .val().trigger('change') fires even when #sellType already says sellDiv (a select() would not). */
const reopenSale = () => {
  cy.window().then((w) => w.$('#sellType').val('sellDiv').trigger('change'))
  cy.get('#sellDiv').should('be.visible')
}

describe('TP-1 — the screens offer the price a purchase or an approval just set', () => {
  let saved = []

  before(() => {
    cy.loginAsOwner()
    cy.request('/getBusinessConfig').then((r) => {
      const all = list(r.body)
      saved = KEYS.map((k) => { const e = all.find((x) => x.key === k); return { k, chosen: !!e && e.isDefault === false, v: e && e.value } })
    })
  })
  beforeEach(() => { cy.loginAsOwner(); KEYS.forEach(reset) })
  after(() => {
    cy.loginAsOwner()
    cy.then(() => saved.forEach((s) => (s.chosen ? set(s.k, s.v) : reset(s.k))))
  })

  it('F1 the till, same section: the pick corrects a price a purchase moved since the page loaded', () => {
    cy.seedProduct({ name: 'PRMTP_' + uniq(), sellingPrice: 200 }).then(({ productId }) => {
      ensureVendor().then((venderId) => {
        cy.visitSaleScreen()
        loadFix()
        cy.get('#sellItemDD option', { timeout: 30000 }).should('have.length.greaterThan', 1)
        pick(productId)
        cy.get('#sellSellRate').should(($i) => expect(Number($i.val()), 'before: the registration price').to.eq(200))

        purchaseFromPage(productId, venderId)
        priceOf(productId).then((p) => expect(p, 'the purchase moved the product price').to.eq(250))

        // Same section, no reopen: the options on screen were built before the purchase.
        cy.get('#sellItemDD').select('', { force: true })
        pick(productId)
        cy.get('#sellSellRate').should(($i) => expect(Number($i.val()), 'the till fills the live price').to.eq(250))
        optionPrice(productId, 250, 'the option now carries the live price')
      })
    })
  })

  it('F2 Purchase → save → Sale: the Sale picker carries the new price before any pick', () => {
    cy.seedProduct({ name: 'PRMTP_' + uniq(), sellingPrice: 200 }).then(({ productId }) => {
      ensureVendor().then((venderId) => {
        /*
         * Starts on Purchase, not Sale: a getUserSell answered after a switch away from Sale is drawn by the other
         * section's branch (the shared loader reads the global getAll at response time) and throws — a separate,
         * pre-existing race (doc §12.7) that made this case flaky. Layer A is about the SHARED picker cache, which the
         * Purchase screen fills exactly as the Sale screen does.
         */
        cy.intercept('GET', '**/getUserPurchase*').as('purchaseGrid')
        cy.openPurchaseSection('purchaseDiv')
        loadFix()
        cy.wait('@purchaseGrid', { timeout: 30000 })
        cy.get(`#purchaseItemDD option[value="${productId}"]`, { timeout: 20000 }).should(($o) =>
          expect(Number($o.attr('data-price')), 'before: the cache holds the registration price').to.eq(200))
        purchaseFromPage(productId, venderId)
        priceOf(productId).then((p) => expect(p, 'the purchase moved the product price').to.eq(250))
        reopenSale()
        optionPrice(productId, 250, 'the Sale picker was rebuilt from the server, not the stale cache')
      })
    })
  })

  it('F3 an approved price: the Sale picker carries it before any pick', () => {
    set('pos.pricing.markupMode', 'approval'); set('pos.pricing.markupPct', '14.5')
    cy.seedProduct({ name: 'PRMTP_' + uniq(), sellingPrice: 200 }).then(({ productId }) => {
      ensureVendor().then((venderId) => {
        const inv = 'PRM-TP-A-' + uniq()
        cy.request({ method: 'POST', url: '/addPurchase', form: true,
          body: { productId, venderId, quantity: 1, purchaseRate: 210, 'stock.bpurchaseRate': 210, 'stock.bsellRate': 250,
                  totalAmount: 210, netAmount: 40, purchaseInvoiceNo: inv } })
          .its('body.status').should('eq', 'SUCCESS')
        priceOf(productId).then((p) => expect(p, 'Approval: the purchase did not move the price').to.eq(200))

        cy.visitSaleScreen()
        loadFix()
        optionPrice(productId, 200, 'before: the price now')
        cy.get('#snavPurchase .snav-btn').click()
        cy.get('#navPriceApprovals').should('be.visible').click()
        cy.get('#PriceApprovalsDiv').should('be.visible')
        cy.get(`#tablePriceApprovals tr[data-product="${productId}"]`, { timeout: 15000 }).as('row')
        cy.intercept('POST', '**/approvePriceChange').as('ok')
        cy.get('@row').find('.pa-approve').click()
        cy.get('[data-ui-confirm="ok"]').click()
        cy.wait('@ok').its('response.body.success').should('eq', true)
        priceOf(productId).then((p) => expect(p, 'approved: 14.5% on 210').to.eq(240.45))
        reopenSale()
        optionPrice(productId, 240.45, 'the Sale picker carries the approved price')
      })
    })
  })
})
