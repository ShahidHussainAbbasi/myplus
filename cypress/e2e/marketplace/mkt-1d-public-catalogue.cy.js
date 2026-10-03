/**
 * MKT-1d — public catalogue: one product, N offers, customer-controlled sort, guardrails.
 * Source §5.4, §7.1–7.3, §7.6, §18.2, §18.4. Run headed: --env mkt=1d
 *
 * Fixture: the SAME canonical phone offered by two sellers, mirroring the source's own example —
 *   SELLER_A  Rs 52,000 · today    · 12 months   (the "Shahzad Mobile Shop" offer)
 *   SELLER_B  Rs 51,500 · tomorrow ·  6 months   (the "Mobile Distributor" offer)
 */
const { gate, uniq, SELLER_A, SELLER_B, API, UI, ok, data, list, post, get, makeSeller, seedProduct,
  a32 } = require('./mkt-helpers')

gate('1d')('MKT-1d — public catalogue and offer comparison', () => {
  const run = uniq()
  const model = `Galaxy A32 T${run}`
  let mktProductId
  const offers = {}

  const publishOffer = (email, price, promiseHours, warrantyMonths) => {
    makeSeller(email)
    return seedProduct({ name: `${model} ${email}` }).then((pid) =>
      post(API.proposeProduct, { sourceProductId: pid, ...a32(run) }).then((r) => {
        const prop = data(r.body)
        cy.loginAsOperator()
        return post(API.decideMatch, { id: prop.id, decision: 'MATCHED', mktProductId }).then((d) => {
          mktProductId = mktProductId || data(d.body).mktProductId
          cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')
          return post(API.saveOffer, { mktProductId, sourceProductId: pid, marketplacePrice: price,
            deliveryArea: 'Karachi', promiseHours, warrantyMonths, returnDays: 7 })
        })
      }).then((r) => {
        const id = data(r.body).id
        post(API.submitOffer, { id })
        cy.loginAsOperator()
        return post(API.decideOffer, { id, decision: 'APPROVED' }).then(() => { offers[email] = id })
      }))
  }

  before(() => {
    publishOffer(SELLER_A, 52000, 4, 12)
    publishOffer(SELLER_B, 51500, 24, 6)
  })

  it('MKT-1d-01 [MKT-R5.4] [MKT-R7.2] a customer searches and sees "Available from 2 sellers · From Rs. 51,500" (real UI)', () => {
    cy.clearCookies()
    cy.visit(UI.publicPage)
    cy.get(UI.search).should('be.visible').type(`${model}{enter}`)
    cy.contains(UI.productCard, model).within(() => {
      cy.get(UI.offerCount).should('have.text', 'Available from 2 sellers')
      cy.get(UI.fromPrice).should('contain', 'From Rs. 51,500')
    })
    cy.contains(UI.productCard, model).click()
    cy.get(UI.offerRow).should('have.length', 2).first().within(() => {
      ;['Rs.', 'Delivery', 'Warranty', 'Rating'].forEach((t) => cy.contains(t).should('be.visible'))
    })
  })

  it('MKT-1d-02 [MKT-R7.1] [MKT-R18.4] the customer\'s sort changes the order on screen', () => {
    cy.visit(`${UI.publicPage}?product=${mktProductId}`)
    cy.get(UI.sort).should('be.visible').select('LOWEST_PRICE')
    cy.get(UI.offerRow).first().should('have.attr', 'data-offer-id', String(offers[SELLER_B]))
    cy.get(UI.sort).select('FASTEST')
    cy.get(UI.offerRow).first().should('have.attr', 'data-offer-id', String(offers[SELLER_A]))
    cy.get(UI.sort).select('WARRANTY')
    cy.get(UI.offerRow).first().should('have.attr', 'data-offer-id', String(offers[SELLER_A]))
  })

  it('MKT-1d-03 [MKT-R7.3] the cheapest offer is never pre-selected: the Buy button names the seller chosen', () => {
    cy.visit(`${UI.publicPage}?product=${mktProductId}`)
    cy.get(UI.buyButton).should('be.disabled')
    cy.get(`${UI.offerRow}[data-offer-id="${offers[SELLER_A]}"] ${UI.chooseOffer}`).click()
    cy.get(UI.buyButton).should('be.enabled').invoke('text').should('match', /^Buy from /)
  })

  it('MKT-1d-04 [MKT-R7.6] [MKT-R20.1] a city the seller does not serve hides its offer; the API agrees with the screen', () => {
    get(`${API.publicOffers(mktProductId)}?city=Lahore`).then((r) => {
      expect(ok(r.body), 'positive control: the read answers').to.eq(true)
      expect(list(r.body)).to.have.length(0)
    })
    cy.visit(`${UI.publicPage}?product=${mktProductId}&city=Lahore`)
    cy.contains('No seller delivers this product to Lahore yet').should('be.visible')
  })

  it('MKT-1d-05 [MKT-R7.6] an unknown sort from the URL falls back to the default, never an error page', () => {
    get(`${API.publicOffers(mktProductId)}?sort=<script>&city=Karachi`).then((r) => {
      expect(ok(r.body)).to.eq(true)
      expect(list(r.body)).to.have.length(2)
    })
  })

  it('MKT-1d-06 [MKT-R18.2] the projection shows when stock was last synced', () => {
    get(`${API.publicOffers(mktProductId)}?city=Karachi`).then((r) => {
      list(r.body).forEach((o) => expect(o).to.include.keys('lastSyncAt', 'availableQty', 'promiseHours', 'rating'))
    })
  })

  it('MKT-1d-07 [MKT-R7.6] a paused offer is not listed and "from" price recomputes', () => {
    cy.loginAs(SELLER_B, 'Demo@2025!', '/getBusinessDashboardStats')
    post(API.saveOffer, { id: offers[SELLER_B], paused: true })
    get(`${API.publicProducts}?q=${encodeURIComponent(model)}`).then((r) => {
      const p = list(r.body).find((x) => x.id === mktProductId)
      expect(p.offerCount).to.eq(1)
      expect(Number(p.fromPrice)).to.eq(52000)
    })
    post(API.saveOffer, { id: offers[SELLER_B], paused: false })
  })
})
