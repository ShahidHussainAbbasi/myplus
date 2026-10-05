/**
 * PR-2 — the markup rule: a selling price suggested (or, in Auto, set) from what a purchase cost.
 *
 * Design: microservices/docs/selling-price-per-purchase-analysis.md §8
 *
 * What each case protects:
 *   M1     the settings exist with the decided defaults (Suggest, NO %, on cost, exact, never lower, no cap)
 *   M2     no % → nothing is suggested (decision 4: no platform default)
 *   M3     the owner's worked example: 14.5% on 100 → 114.50 on cost, 116.96 as a margin, 115 rounded to 5
 *   M4     the product's own % wins over the business's; it round-trips; a typo is refused
 *   M5–M8  Auto on a real purchase: sets the rule's price (history MARKUP), never lowers, respects the cap,
 *          and Keep beats Auto
 *   M9–M10 the purchase form: Suggest offers "Use 114.50"; Auto says what will happen, or why it is held back
 *   M11    another tenant's product is not priced
 *
 * Tenant: owner.business@ (POS). Server state: every pricing setting this spec touches is put back EXACTLY in after().
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/pricing-markup.cy.js --headed --browser chrome
 */
const KEYS = ['pos.pricing.purchaseMode', 'pos.pricing.markupMode', 'pos.pricing.markupPct', 'pos.pricing.markupBasis',
  'pos.pricing.markupRounding', 'pos.pricing.markupNeverLower', 'pos.pricing.markupMaxRisePct']
const GW = 'http://localhost:8765/api/business'
const STAMP = Date.now()
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(body && body[k])) return body[k]; return [] }
const config = () => cy.request('/getBusinessConfig').then((r) => cy.wrap(list(r.body)))
const set = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value: String(value) }, failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success, `save ${key}=${value}: ${JSON.stringify(r.body)}`).to.eq(true))
const reset = (key) => cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key }, failOnStatusCode: false })
const resetAll = () => KEYS.forEach(reset)

const suggest = (productId, cost) =>
  cy.request({ url: `/suggestedPrice?productId=${productId}&cost=${cost}`, failOnStatusCode: false }).then((r) => {
    expect(r.body && r.body.status, `suggestedPrice: ${JSON.stringify(r.body).slice(0, 300)}`).to.eq('SUCCESS')
    return cy.wrap(r.body.object)
  })
const product = (id) => cy.request('/getCatalogProduct?id=' + id).then((r) => cy.wrap(r.body.data))
const history = (id) => cy.request('/productPriceHistory?productId=' + id).then((r) => cy.wrap(r.body.data.history))
const seed = (price) => cy.seedProduct({ name: 'PRM2_' + uniq(), sellingPrice: price }).then((p) => cy.wrap(p.productId))
const setProductMarkup = (id, pct) => product(id).then((p) =>
  cy.request({ method: 'POST', url: '/updateProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: Object.assign({}, p, { markupPct: pct }) }))

let vendorId = null
const ensureVendor = () => {
  if (vendorId) return cy.wrap(vendorId)
  const vname = 'PRM2_V_' + STAMP
  return cy.ensureCompany().then((companyId) => {
    cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
      body: { name: vname, companyId, mobile: '0302' + String(STAMP).slice(-7), email: 'prm2' + STAMP + '@t.com' } })
    return cy.request('/getUserVenders').then((vr) => {
      const html = String(vr.body && vr.body.object != null ? vr.body.object : vr.body)
      vendorId = (new RegExp('<option value=(\\d+)[^>]*>' + vname).exec(html) || [])[1]
      expect(vendorId, 'spec vendor').to.exist
      return cy.wrap(vendorId)
    })
  })
}
const purchase = (productId, cost, sell, inv) => ensureVendor().then((venderId) =>
  cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: { productId, venderId, quantity: 1, purchaseRate: cost, 'stock.bpurchaseRate': cost, 'stock.bsellRate': sell,
            totalAmount: cost, netAmount: sell - cost, purchaseInvoiceNo: inv } })
    .then((p) => expect(p.body.status, `addPurchase ${inv}: ${JSON.stringify(p.body).slice(0, 300)}`).to.eq('SUCCESS')))

function openPurchaseFor(productId) {
  cy.visit('/businessDashboard')
  cy.openPurchaseSection('purchaseDiv')
  cy.get('#newPurchase').click()
  cy.get('#PurchaseModal').should('have.class', 'open')
  cy.settled('#purchaseInvoiceNo')
  cy.intercept('GET', '/productStock*').as('prefill')
  cy.get('#purchaseItemDD').select(String(productId), { force: true })
  cy.wait('@prefill', { timeout: 15000 })
}

describe('PR-2 — the markup rule', () => {
  let before0 = null   // { key: { chosen, value } } as this tenant had them

  before(() => {
    cy.loginAsOwner()
    config().then((all) => {
      before0 = {}
      KEYS.forEach((k) => { const e = all.find((x) => x.key === k); before0[k] = e ? { chosen: e.isDefault === false, value: e.value } : { chosen: false } })
    })
  })
  beforeEach(() => { cy.loginAsOwner(); resetAll() })
  after(() => {
    cy.loginAsOwner()
    cy.then(() => KEYS.forEach((k) => (before0[k].chosen ? set(k, before0[k].value) : reset(k))))
    config().then((all) => KEYS.forEach((k) => {
      const e = all.find((x) => x.key === k)
      if (before0[k].chosen) expect(e.value, k + ' restored').to.eq(before0[k].value)
      else expect(e.isDefault, k + ' back to never-chosen').to.eq(true)
    }))
  })

  it('M1 the settings are offered under Purchasing with the decided defaults', () => {
    config().then((all) => {
      const v = (k) => { const e = all.find((x) => x.key === k); expect(e, k).to.be.an('object'); expect(e.group, k).to.eq('Purchasing'); return e }
      expect(v('pos.pricing.markupMode').value).to.eq('suggest')
      expect(v('pos.pricing.markupMode').options.map((o) => o.value)).to.deep.eq(['off', 'suggest', 'auto'])
      expect(Number(v('pos.pricing.markupPct').value), 'no platform default %').to.eq(0)
      expect(v('pos.pricing.markupBasis').value).to.eq('markup')
      expect(v('pos.pricing.markupRounding').options.map((o) => o.value)).to.deep.eq(['exact', 'up1', 'near5', 'near10'])
      expect(String(v('pos.pricing.markupNeverLower').value)).to.eq('true')
      expect(Number(v('pos.pricing.markupMaxRisePct').value)).to.eq(0)
    })
  })

  it('M2 with no % set, nothing is suggested', () => {
    seed(200).then((id) => suggest(id, 100).then((s) => {
      expect(s.mode).to.eq('suggest')
      expect(s.price, 'no rule, no price').to.eq(null)
      expect(s.autoApplies).to.eq(false)
    }))
  })

  it('M3 14.5% on a cost of 100: 114.50 on cost, 116.96 as a margin, 115 rounded to the nearest 5', () => {
    set('pos.pricing.markupPct', '14.5')
    seed(200).then((id) => {
      suggest(id, 100).then((s) => { expect(Number(s.price)).to.eq(114.5); expect(s.basis).to.eq('markup'); expect(s.pctSource).to.eq('BUSINESS') })
      set('pos.pricing.markupBasis', 'margin')
      suggest(id, 100).then((s) => expect(Number(s.price)).to.eq(116.96))
      set('pos.pricing.markupRounding', 'near5')
      suggest(id, 100).then((s) => { expect(Number(s.raw)).to.eq(116.96); expect(Number(s.price)).to.eq(115) })
    })
  })

  it('M4 the product\'s own % wins, round-trips on the product, and a typo is refused', () => {
    set('pos.pricing.markupPct', '14.5')
    seed(200).then((id) => {
      setProductMarkup(id, 30).then((r) => expect(r.body && r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true))
      product(id).then((p) => expect(Number(p.markupPct)).to.eq(30))
      suggest(id, 100).then((s) => { expect(Number(s.price)).to.eq(130); expect(s.pctSource).to.eq('PRODUCT') })
      setProductMarkup(id, -5).then((r) => {
        expect(r.body && r.body.success).to.not.eq(true)
        expect(JSON.stringify(r.body)).to.contain('between 0 and 1000')
      })
      product(id).then((p) => expect(Number(p.markupPct), 'the refused value was not stored').to.eq(30))
      setProductMarkup(id, null)
      product(id).then((p) => expect(p.markupPct, 'cleared = the business rate').to.eq(null))
    })
  })

  it('M5 Auto: a purchase sets the RULE\'s price, and the history says Markup rule with the bill', () => {
    set('pos.pricing.markupMode', 'auto'); set('pos.pricing.markupPct', '15')
    const inv = 'PRM2-A-' + STAMP
    seed(200).then((id) => {
      purchase(id, 210, 250, inv)   // the bill's S/U is 250; the rule says 210 × 1.15 = 241.50
      product(id).then((p) => { expect(Number(p.sellingPrice), 'the rule, not the bill').to.eq(241.5); expect(Number(p.lastPurchaseRate)).to.eq(210) })
      history(id).then((h) => { expect(h[0].source).to.eq('MARKUP'); expect(h[0].ref).to.eq(inv); expect(Number(h[0].newPrice)).to.eq(241.5) })
    })
  })

  it('M6 Auto never lowers a price: a 300 product bought at 210 stays 300 (cost still recorded)', () => {
    set('pos.pricing.markupMode', 'auto'); set('pos.pricing.markupPct', '15')
    seed(300).then((id) => {
      suggest(id, 210).then((s) => { expect(s.guard).to.eq('NEVER_LOWER'); expect(s.autoApplies).to.eq(false) })
      purchase(id, 210, 250, 'PRM2-L-' + STAMP)
      product(id).then((p) => { expect(Number(p.sellingPrice)).to.eq(300); expect(Number(p.lastPurchaseRate)).to.eq(210) })
      history(id).then((h) => expect(h.map((x) => x.source)).to.deep.eq(['MANUAL']))
    })
  })

  it('M7 Auto respects the rise cap: 200 → 241.50 is past a 10% cap, so the price stays 200', () => {
    set('pos.pricing.markupMode', 'auto'); set('pos.pricing.markupPct', '15'); set('pos.pricing.markupMaxRisePct', '10')
    seed(200).then((id) => {
      suggest(id, 210).then((s) => expect(s.guard).to.eq('MAX_RISE'))
      purchase(id, 210, 250, 'PRM2-C-' + STAMP)
      product(id).then((p) => expect(Number(p.sellingPrice)).to.eq(200))
    })
  })

  it('M8 Keep beats Auto: "never changes the price" means never', () => {
    set('pos.pricing.purchaseMode', 'keep'); set('pos.pricing.markupMode', 'auto'); set('pos.pricing.markupPct', '15')
    seed(200).then((id) => {
      suggest(id, 210).then((s) => expect(s.autoApplies, 'Auto does not apply under Keep').to.eq(false))
      purchase(id, 210, 250, 'PRM2-K-' + STAMP)
      product(id).then((p) => { expect(Number(p.sellingPrice)).to.eq(200); expect(Number(p.lastPurchaseRate)).to.eq(210) })
    })
  })

  it('M9 Suggest on the purchase form: "Suggested 114.50" with a Use button that fills S/U', () => {
    set('pos.pricing.markupPct', '14.5')
    seed(200).then((id) => {
      openPurchaseFor(id)
      cy.get('#purchasePurchaseRate').clear().type('100')
      cy.get('#purchaseSuggest', { timeout: 10000 }).should('be.visible')
        .and('have.attr', 'data-mode', 'suggest').and('have.attr', 'data-price', '114.50')
        .and('contain', '114.50').and('contain', '14.5% on cost')
      cy.get('#purchaseSuggestApply').click()
      cy.get('#purchaseSellRate').should('have.value', '114.50')
      cy.get('#purchasePriceEffect').should('have.attr', 'data-effect', 'change').and('contain', '114.50')
      cy.get('#purchaseSuggestApply').should('not.exist')   // already using it
    })
  })

  it('M10 Auto on the purchase form: says what will be set — or why it is held back', () => {
    set('pos.pricing.markupMode', 'auto'); set('pos.pricing.markupPct', '15')
    seed(200).then((id) => {
      openPurchaseFor(id)
      cy.window().its('posPurchasePriceMode').should('eq', 'latest')
      cy.get('#purchasePurchaseRate').clear().type('210')
      cy.get('#purchasePriceEffect', { timeout: 10000 }).should('have.attr', 'data-effect', 'auto')
        .and('contain', '200.00').and('contain', '241.50')
      cy.get('#purchaseSuggest').should('not.be.visible')
      cy.get('#purchasePurchaseRate').clear().type('150')   // 172.50 < 200 → held back
      cy.get('#purchasePriceEffect', { timeout: 10000 }).should('have.attr', 'data-effect', 'held').and('contain', '172.50')
    })
  })

  it('M11 another tenant\'s product is not priced', () => {
    seed(200).then((id) => {
      cy.asOtherTenant((auth) => {
        cy.request({ url: `${GW}/suggestedPrice?productId=${id}&cost=100`, headers: auth, failOnStatusCode: false }).then((r) => {
          const refused = r.status >= 400 || (r.body && r.body.status !== 'SUCCESS')
          expect(refused, 'cross-tenant: ' + r.status + ' ' + JSON.stringify(r.body).slice(0, 200)).to.eq(true)
        })
      }, 'owner.pharma@myplus.com')
    })
  })
})
