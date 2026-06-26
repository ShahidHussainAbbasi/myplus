/**
 * E-commerce E2b (slice 48) — storefront online payment (sandbox).
 * Card → PaymentGateway charge: success marks the order PAID/CARD with a charge ref; the decline test card
 * (4000 0000 0000 0002 → token "fail") blocks the order entirely; COD stays PENDING. Run headed + slow.
 */
describe('E-commerce — storefront online payment', () => {
  let orgId, productId
  const pname = 'PayShop_' + Date.now()

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => {
      const o = (r.body.collection || [])[0] || {}
      orgId = o.id || o.organizationId || o.orgId
    })
    // Checkout now reserves stock (slice 49), so the product must have inventory — create it and stock it.
    cy.request({ method: 'POST', url: '/addProduct', body: { name: pname, sku: 'PAY' + Date.now(), sellingPrice: 20, taxRate: 0, unit: 'pcs' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => {
        productId = r.body.data.id
        return cy.request({ method: 'POST', url: '/addProductStock', body: { productId, quantity: 20 }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      })
  })

  const checkout = (body) => cy.request({
    method: 'POST', url: '/storefront/checkout',
    body: Object.assign({ organizationId: orgId, customerContact: '0300PAY', shippingAddress: '5 Pay St', total: 20, items: [{ productId, quantity: 1, price: 20 }] }, body),
    headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
  })

  it('a card payment confirms the order as PAID with a charge ref', () => {
    const buyer = 'CardBuyer_' + Date.now()
    checkout({ customerName: buyer, paymentMode: 'CARD', cardToken: 'tok_sandbox' }).then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.paymentMode).to.eq('CARD')
      expect(r.body.data.paymentStatus).to.eq('PAID')
      expect(r.body.data.paymentRef).to.match(/^ch_sandbox_/)
    })
  })

  it('a declined card blocks the order — nothing is created', () => {
    const buyer = 'Declined_' + Date.now()
    checkout({ customerName: buyer, paymentMode: 'CARD', cardToken: 'fail' }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(false)
    })
    cy.loginAsMarketplace()
    cy.request('/getOrders').then((r) => {
      const mine = (r.body.data || []).find((o) => o.customerName === buyer)
      expect(mine, 'declined order must not exist').to.not.exist
    })
  })

  it('a COD order stays PENDING', () => {
    checkout({ customerName: 'CodBuyer_' + Date.now(), paymentMode: 'COD' }).then((r) => {
      expect(r.body.success).to.eq(true)
      expect(r.body.data.paymentMode).to.eq('COD')
      expect(r.body.data.paymentStatus).to.eq('PENDING')
    })
  })

  it('the storefront UI pays by card and confirms the order', () => {
    cy.visit('/store?org=' + orgId)
    cy.contains('.card .name', pname, { timeout: 10000 }).should('exist')
    cy.contains('.card', pname).find('.add').click()
    cy.get('#checkout').should('be.visible')
    cy.get('#cName').type('UICard_' + Date.now())
    cy.get('#tabCard').click()
    cy.get('#cardForm').should('be.visible')
    cy.get('#payBtn').should('contain', 'Pay now').click()
    cy.get('#orderDone', { timeout: 10000 }).should('contain', 'Payment received')
  })
})
