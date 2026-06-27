/**
 * E-commerce E4 (slice 61) — storefront customer accounts. Register/login (BCrypt), orders link to the account,
 * "My Orders" lists them; a wrong password is rejected. Run headed.
 */
describe('E-commerce — storefront customer accounts', () => {
  let orgId, productId, token
  const ts = Date.now()
  const email = 'shopper' + ts + '@example.com'
  const pass = 'secret123'

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => { orgId = ((r.body.collection || [])[0] || {}).id })
    cy.request({ method: 'POST', url: '/addProduct', body: { name: 'AcctProd_' + ts, sku: 'AC' + ts, sellingPrice: 15, taxRate: 0, unit: 'pcs' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => {
        productId = r.body.data.id
        return cy.request({ method: 'POST', url: '/addProductStock', body: { productId, quantity: 10 }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      })
  })

  it('register, reject wrong password, login, link a checkout, then list My Orders', () => {
    cy.request({ method: 'POST', url: '/storefront/register', body: { organizationId: orgId, email, password: pass, name: 'Shopper' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        expect(r.body.data.token).to.be.a('string')
      })

    // wrong password is rejected
    cy.request({ method: 'POST', url: '/storefront/login', body: { organizationId: orgId, email, password: 'WRONG' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => { expect(r.body.success, 'wrong password rejected').to.eq(false) })

    // correct login → token
    cy.request({ method: 'POST', url: '/storefront/login', body: { organizationId: orgId, email, password: pass }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => { expect(r.body.success).to.eq(true); token = r.body.data.token })

    let orderId
    cy.then(() => {
      cy.request({
        method: 'POST', url: '/storefront/checkout',
        body: { organizationId: orgId, customerName: 'Shopper', customerContact: email, shippingAddress: '1 Acct St', total: 15, paymentMode: 'COD', items: [{ productId, quantity: 1, price: 15 }], customerToken: token },
        headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      }).then((r) => { expect(r.body.success, JSON.stringify(r.body)).to.eq(true); orderId = r.body.data.id })
    })

    cy.then(() => {
      cy.request('/storefront/myorders?token=' + encodeURIComponent(token)).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        const ids = (r.body.data || []).map((o) => o.id)
        expect(ids, 'my-orders includes the linked order').to.include(orderId)
      })
    })
  })

  it('the store page account panel signs up and shows the shopper', () => {
    cy.visit('/store?org=' + orgId)
    const e2 = 'ui' + Date.now() + '@example.com'
    cy.get('#acEmail', { timeout: 10000 }).type(e2)
    cy.get('#acPass').type('secret123')
    cy.get('#acName').type('UI Shopper')
    cy.contains('#acctOut button', 'Sign up').click()
    cy.get('#acctIn', { timeout: 10000 }).should('be.visible')
    cy.get('#acWho').should('contain', 'UI Shopper')
  })
})
