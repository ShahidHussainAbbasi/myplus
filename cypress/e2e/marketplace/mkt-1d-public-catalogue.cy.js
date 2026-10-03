/**
 * MKT-1d — public catalogue: one product, N offers, customer-controlled sort, guardrails.
 * Source §5.4, §7.1–7.3, §7.6, §18.2, §18.4. Run headed: --env mkt=1d
 * Contract: microservices/docs/slices/mkt-1d-public-catalogue.md
 *
 * Fixture: the SAME canonical phone offered by two sellers, mirroring the source's own example —
 *   SELLER_A  Rs 52,000 ·  4 h · 12-month warranty
 *   SELLER_B  Rs 51,500 · 24 h ·  6-month warranty
 * Both deliver to Karachi only.
 */
const { gate, uniq, SELLER_A, SELLER_B, API, UI, ok, data, list, post, get, expectRefused, seedPolicies,
  publishOffer, a32 } = require('./mkt-helpers')

gate('1d')('MKT-1d — public catalogue and offer comparison', () => {
  const run = uniq()
  const model = a32(run).model
  const offers = {}
  const names = {}
  let mktProductId, w12, w6, ret7

  before(() => {
    seedPolicies(run, { months: 12, provider: 'Samsung Pakistan' }).then((p) => { w12 = p.warranty; ret7 = p.returns })
    seedPolicies(`${run}b`, { months: 6, provider: 'Shop warranty' }).then((p) => { w6 = p.warranty })
    cy.loginAsOperator()
    post(API.defaultSort, { sort: 'RECOMMENDED' })
    cy.then(() => publishOffer(SELLER_A, { run, price: 52000, promiseHours: 4, warrantyPolicyId: w12, returnPolicyId: ret7 }))
      .then((o) => { offers[SELLER_A] = o.offerId; mktProductId = o.mktProductId })
    cy.then(() => publishOffer(SELLER_B, { run, price: 51500, promiseHours: 24, warrantyPolicyId: w6, returnPolicyId: ret7, mktProductId }))
      .then((o) => { offers[SELLER_B] = o.offerId; expect(o.mktProductId, 'one canonical product').to.eq(mktProductId) })
    cy.then(() => get(`${API.publicOffers(mktProductId)}?city=Karachi`)).then((r) =>
      list(r.body).forEach((o) => { names[o.offerId] = o.sellerName }))
  })

  it('MKT-1d-01 [MKT-R5.4] [MKT-R7.2] a customer searches and sees "Available from 2 sellers · From Rs. 51,500" (real UI)', () => {
    cy.clearCookies()
    cy.visit(UI.publicPage)
    cy.get(UI.city).clear().type('Karachi')
    cy.get(UI.search).should('be.visible').clear().type(`${model}{enter}`)
    cy.url().should('include', 'q=')
    cy.contains(UI.productCard, model).within(() => {
      cy.get(UI.offerCount).should('have.text', 'Available from 2 sellers')
      cy.get(UI.fromPrice).should('have.text', 'From Rs. 51,500')
    })
    cy.contains(UI.productCard, model).find('a').click()
    cy.url().should('include', `product=${mktProductId}`)
    cy.get(UI.offerRow).should('have.length', 2)
    cy.get(`${UI.offerRow}[data-offer-id="${offers[SELLER_A]}"]`).within(() => {
      cy.contains(names[offers[SELLER_A]])
      cy.contains('Rs. 52,000')
      cy.contains('Samsung Pakistan')
      cy.contains('12 months')
      cy.contains('7 days')
    })
    cy.go('back')
    cy.contains(UI.productCard, model).should('be.visible')   // back returns to the results
  })

  it('MKT-1d-02 [MKT-R7.1] [MKT-R18.4] the customer\'s sort changes the order on screen and survives a reload', () => {
    cy.visit(`${UI.publicPage}?product=${mktProductId}&city=Karachi`)
    cy.get(UI.sort).should('be.visible').select('LOWEST_PRICE')
    cy.get(UI.offerRow).first().should('have.attr', 'data-offer-id', String(offers[SELLER_B]))
    cy.get(UI.sort).select('FASTEST')
    cy.get(UI.offerRow).first().should('have.attr', 'data-offer-id', String(offers[SELLER_A]))
    cy.get(UI.sort).select('WARRANTY')
    cy.get(UI.offerRow).first().should('have.attr', 'data-offer-id', String(offers[SELLER_A]))
    cy.get(UI.sort).select('LOWEST_PRICE')
    cy.reload()
    cy.get(UI.sort).should('have.value', 'LOWEST_PRICE')
    cy.get(UI.offerRow).first().should('have.attr', 'data-offer-id', String(offers[SELLER_B]))
  })

  it('MKT-1d-03 [MKT-R7.3] nothing is pre-selected; the Buy button names the seller chosen (keyboard)', () => {
    cy.visit(`${UI.publicPage}?product=${mktProductId}&city=Karachi&sort=LOWEST_PRICE`)
    cy.get(UI.offerRow).should('have.length', 2)
    cy.get(UI.chooseOffer).should('not.be.checked')
    cy.get(UI.buyButton).should('be.disabled')
    // choose the dearer seller: a cheaper offer existing must not change what the customer picked
    cy.get(`${UI.offerRow}[data-offer-id="${offers[SELLER_A]}"] ${UI.chooseOffer}`).focus().check()
    cy.get(UI.buyButton).should('be.enabled').and('have.text', `Buy from ${names[offers[SELLER_A]]}`)
    cy.get(UI.buyButton).click()
    // MKT-1e: Buy opens the checkout for exactly the seller chosen, at that seller's price
    cy.get('#mktCoSeller').should('have.text', names[offers[SELLER_A]])
    cy.get('#mktCoTotal').should('have.text', 'Rs. 52,000')
  })

  it('MKT-1d-04 [MKT-R7.6] [MKT-R20.1] a city the sellers do not serve: no card, no offers, and the page says so', () => {
    get(`${API.publicOffers(mktProductId)}?city=Lahore`).then((r) => {
      expect(ok(r.body), 'positive control: the read answers').to.eq(true)
      expect(list(r.body)).to.have.length(0)
    })
    get(`${API.publicProducts}?q=${encodeURIComponent(model)}&city=Lahore`).then((r) => {
      expect(ok(r.body)).to.eq(true)
      expect(list(r.body).map((p) => p.id)).to.not.include(mktProductId)
    })
    cy.visit(`${UI.publicPage}?product=${mktProductId}&city=Lahore`)
    cy.contains('No seller delivers this product to Lahore yet').should('be.visible')
    cy.get(UI.buyButton).should('be.disabled')
  })

  it('MKT-1d-05 [MKT-R7.6] an unknown sort from the URL falls back to the default, never an error page', () => {
    get(`${API.publicOffers(mktProductId)}?sort=${encodeURIComponent('<script>')}&city=Karachi`).then((r) => {
      expect(ok(r.body)).to.eq(true)
      expect(list(r.body)).to.have.length(2)
    })
    cy.visit(`${UI.publicPage}?product=${mktProductId}&city=Karachi&sort=${encodeURIComponent('<script>')}`)
    cy.get(UI.offerRow).should('have.length', 2)
    cy.get(UI.sort).should('have.value', 'RECOMMENDED')
  })

  it('MKT-1d-06 [MKT-R18.2] each offer says when its stock was last checked', () => {
    get(`${API.publicOffers(mktProductId)}?city=Karachi`).then((r) => {
      list(r.body).forEach((o) => {
        expect(o).to.include.keys('lastSyncAt', 'availableQty', 'promiseHours', 'rating')
        expect(o.lastSyncAt, 'a listed offer has been confirmed with inventory').to.not.eq(null)
      })
    })
    cy.visit(`${UI.publicPage}?product=${mktProductId}&city=Karachi`)
    cy.get(UI.offerRow).first().find('.mkt-stock-checked').invoke('text').should('match', /Stock checked/)
  })

  it('MKT-1d-07 [MKT-R7.6] [MKT-R5.4] a paused offer leaves the card and the table together', () => {
    cy.loginAs(SELLER_B, 'Demo@2025!', '/getBusinessDashboardStats')
    post(API.saveOffer, { id: offers[SELLER_B], paused: true }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    cy.clearCookies()
    get(`${API.publicProducts}?q=${encodeURIComponent(model)}&city=Karachi`).then((r) => {
      const p = list(r.body).find((x) => x.id === mktProductId)
      expect(p.offerCount).to.eq(1)
      expect(Number(p.fromPrice)).to.eq(52000)
    })
    get(`${API.publicOffers(mktProductId)}?city=Karachi`).then((r) => expect(list(r.body)).to.have.length(1))
    cy.loginAs(SELLER_B, 'Demo@2025!', '/getBusinessDashboardStats')
    post(API.saveOffer, { id: offers[SELLER_B], paused: false })
  })

  it('MKT-1d-08 [MKT-R7.4] [MKT-R18.4] the operator sets the default order; a tenant cannot (positive control)', () => {
    cy.loginAsOperator()
    post(API.defaultSort, { sort: 'LOWEST_PRICE' }).then((r) => expect(ok(r.body), JSON.stringify(r.body)).to.eq(true))
    get(API.publicProduct(mktProductId)).then((r) => expect(data(r.body).defaultSort).to.eq('LOWEST_PRICE'))
    get(`${API.publicOffers(mktProductId)}?city=Karachi`).then((r) =>
      expect(list(r.body)[0].offerId, 'no sort given → the operator default').to.eq(offers[SELLER_B]))
    post(API.defaultSort, { sort: 'NOT_A_SORT' }).then((r) => expectRefused(r))
    cy.loginAs(SELLER_A, 'Demo@2025!', '/getBusinessDashboardStats')
    post(API.defaultSort, { sort: 'FASTEST' }).then((r) => expect(ok(r.body), 'tenant refused').to.eq(false))
    cy.loginAsOperator()
    get(API.defaultSort).then((r) => expect(data(r.body).sort, 'unchanged by the tenant').to.eq('LOWEST_PRICE'))
    post(API.defaultSort, { sort: 'RECOMMENDED' })
  })

  it('MKT-1d-09 [MKT-R7.6] search text is data: wildcards match literally and nothing errors', () => {
    ;['%', '_', '\\', "' OR 1=1 --"].forEach((q) =>
      get(`${API.publicProducts}?q=${encodeURIComponent(q)}&city=Karachi`).then((r) => {
        expect(ok(r.body), `q=${q}`).to.eq(true)
        expect(list(r.body).map((p) => p.id), `q=${q} must not match everything`).to.not.include(mktProductId)
      }))
    get(`${API.publicProducts}?q=${encodeURIComponent(model.toLowerCase())}&city=Karachi`).then((r) =>
      expect(list(r.body).map((p) => p.id), 'positive control: case-insensitive match').to.include(mktProductId))
  })

  it('MKT-1d-10 [MKT-R7.6] an unknown product reads "No such product." and offers nothing', () => {
    get(API.publicProduct(999999999)).then((r) => expectRefused(r, 'No such product'))
    cy.visit(`${UI.publicPage}?product=999999999`)
    cy.contains('No such product.').should('be.visible')
  })
})
