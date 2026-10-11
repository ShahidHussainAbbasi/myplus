/**
 * H6-C ⚠ MONEY — a coupon's window is the SHOP's local time (typed in a datetime field), and is compared in the shop's
 * zone. It was compared with the server clock, which is UTC in the containers: in Pakistan a coupon started 5 hours
 * late and kept discounting 5 hours after it ended.
 *
 * Both cases are built to FAIL on the old code: "started an hour ago" was "not yet valid" (UTC is 5 h behind), and
 * "ended an hour ago" still discounted. The shop's "now" is computed here from the real clock in Asia/Karachi
 * (Intl), independently of the server.
 *
 * Server state: three coupons per run (unique codes) on the marketplace demo; no order is placed (quotes only). There is no
 * delete for a coupon, so each one cleans up by itself: C-01's ends an hour after it starts, C-02's has already ended.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/business/coupon-window-zone.cy.js"
 */
describe('H6-C — a coupon window is the shop\'s local time', () => {
  let orgId, productId
  const tag = Date.now()
  const name = 'CouponTz_' + tag

  /** The shop's wall clock (Asia/Karachi), shifted by `hours`, as the datetime-local text a person would type. */
  const karachi = (hours) => {
    const d = new Date(Date.now() + hours * 3600 * 1000)
    // sv-SE formats as "YYYY-MM-DD HH:mm:ss"
    return d.toLocaleString('sv-SE', { timeZone: 'Asia/Karachi', hour12: false }).replace(' ', 'T').slice(0, 16)
  }
  const quote = (code) => cy.request({ method: 'POST', url: '/storefront/cart/add', headers: { 'Content-Type': 'application/json' },
    body: { organizationId: orgId, productId, quantity: 2 } })
    .then((r) => cy.request('/storefront/checkout/quote?org=' + orgId + '&cartToken=' + r.body.data.cartToken
                            + '&shippingMethod=PICKUP&couponCode=' + code))
    .its('body.data')

  before(() => {
    cy.loginAsMarketplace()
    cy.request('/getMyOrganizations').then((r) => { orgId = ((r.body.collection || [])[0] || {}).id })
    cy.request({ method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { name, sku: 'CT' + tag, sellingPrice: 10, taxRate: 0, unit: 'pcs' } })
    cy.then(() => cy.request('/storefront/products?org=' + orgId).then((r) => {
      const p = (r.body.data || []).find((x) => x.name === name)
      productId = p && p.id
      expect(productId, 'product created').to.be.ok
    }))
    cy.then(() => cy.request({ method: 'POST', url: '/addProductStock', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
      body: { productId, quantity: 20 } }))
  })

  beforeEach(() => cy.loginAsMarketplace())

  it('C-01 a coupon that started an hour ago (shop time) gives its discount now', () => {
    const code = 'TZSTART' + tag
    cy.request({ method: 'POST', url: '/addCoupon', headers: { 'Content-Type': 'application/json' },
      body: { code, type: 'PERCENT', value: 10, startsAt: karachi(-1), endsAt: karachi(1) } }).its('body.success').should('eq', true)
    quote(code).then((q) => {
      expect(q.couponMessage || q.message || null, JSON.stringify(q)).to.not.match(/not yet valid/i)
      expect(Number(q.discount), 'old code: "not yet valid" for 5 more hours').to.eq(2)   // 10% of 20
    })
  })

  it('C-03 [H6 C4] the window typed in shop time is held as the UTC instant', () => {
    // C4: typed in the business's zone, stored as UTC like every other time — 10:30 Karachi is held as 05:30.
    // RED on the code before C4: the window came back exactly as typed.
    const code = 'TZUTC' + tag
    const typed = karachi(2)                                    // "yyyy-MM-ddTHH:mm", shop wall time
    cy.request({ method: 'POST', url: '/addCoupon', headers: { 'Content-Type': 'application/json' },
      body: { code, type: 'PERCENT', value: 10, startsAt: typed, endsAt: karachi(3) } }).its('body.success').should('eq', true)
    cy.request('/getCoupons').its('body').then((b) => {
      const list = b.data || b.collection || b
      const c = (Array.isArray(list) ? list : []).find((x) => x.code === code)
      expect(c, 'the new coupon in the list').to.exist
      // expected: the typed wall time read in Asia/Karachi (UTC+5, no DST), as UTC — computed here
      const [d, hm] = typed.split('T'); const [y, mo, da] = d.split('-').map(Number); const [h, mi] = hm.split(':').map(Number)
      const utc = new Date(Date.UTC(y, mo - 1, da, h - 5, mi)).toISOString().slice(0, 16)
      expect(String(c.startsAt).slice(0, 16), `typed ${typed} Karachi`).to.eq(utc)
    })
  })

  it('C-02 a coupon that ended an hour ago (shop time) gives no discount', () => {
    const code = 'TZEND' + tag
    cy.request({ method: 'POST', url: '/addCoupon', headers: { 'Content-Type': 'application/json' },
      body: { code, type: 'PERCENT', value: 10, endsAt: karachi(-1) } }).its('body.success').should('eq', true)
    quote(code).then((q) => {
      expect(Number(q.discount), 'old code: still discounting for 4 more hours — ' + JSON.stringify(q)).to.eq(0)
    })
  })
})
