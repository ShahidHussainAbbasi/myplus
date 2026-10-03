/**
 * Multi-seller marketplace (MKT) — shared gate helpers and THE CONTRACT the implementation must satisfy.
 *
 * Programme: microservices/docs/marketplace-multiseller-design.md
 * Test plan: microservices/docs/marketplace-multiseller-test-plan.md
 *
 * These specs are written BEFORE the code (SAAS-BUILD-STANDARDS, "The gate is written BEFORE the implementation").
 * Every route and selector below is a requirement on the implementation, not a description of it: if a slice
 * names something differently, change it HERE and in the design's §6.1/§6.5 in the same commit.
 *
 * ── Opt-in, per slice ────────────────────────────────────────────────────────────────────────────────────────
 * Not built yet ⇒ not run by default. `cypress.config.js` forbids excludeSpecPattern for this (it also blocks a
 * named --spec), so each spec opts in on Cypress.env, the `education/demo.cy.js` pattern:
 *
 *     npx cypress run --headed --env mkt=1b,1c --spec 'cypress/e2e/marketplace/**'
 *     npx cypress run --headed --env mkt=all   --spec 'cypress/e2e/marketplace/**'
 *
 * A slice's spec is switched on when that slice is implemented, and is then its gate.
 */

const enabled = () => String(Cypress.env('mkt') || '').split(',').map((s) => s.trim()).filter(Boolean)

/** `describe` when this slice is switched on for the run, `describe.skip` (reported as pending) otherwise. */
const gate = (slice) => (enabled().includes('all') || enabled().includes(slice) ? describe : describe.skip)

const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

/**
 * Who plays which part (GATE-RUNBOOK §1: log in as the tenant the feature belongs to).
 *   SELLER_A  owner.business@  — a retail counter: the tier ladder (admin./user.) lives in this org
 *   SELLER_B  owner.mobile@    — a mobile shop: the second seller of the SAME phone, the "2 sellers" case
 *   OUTSIDER  owner.pharma@    — never entitled: the cross-tenant / not-entitled control
 *   OPERATOR  admin@myplus.com — MaxTheService staff (ROLE_ADMIN), not a tenant
 */
const SELLER_A = 'owner.business@myplus.com'
const SELLER_B = 'owner.mobile@myplus.com'
const OUTSIDER = 'owner.pharma@myplus.com'

/** Monolith flat routes (ARCHITECTURE-MULTITENANCY §1) — each proxies /api/marketplace/mkt/** and relays the message. */
const API = {
  // public (anonymous)
  publicProducts: '/marketplace/public/products',            // ?q=&city=&page=
  publicOffers: (id) => `/marketplace/public/products/${id}/offers`, // ?sort=&city=&qty=
  // seller (MKT_SELL + capability marketplaceSelling + operator entitlement)
  acceptAgreement: '/mkt/acceptAgreement',
  proposeProduct: '/mkt/proposeProduct',
  myProposals: '/mkt/myProposals',
  saveOffer: '/mkt/saveOffer',
  submitOffer: '/mkt/submitOffer',
  myOffers: '/mkt/myOffers',
  getOffer: (id) => `/mkt/getOffer?id=${id}`,
  incomingOrders: '/mkt/incomingOrders',
  acceptOrder: '/mkt/acceptOrder',
  rejectOrder: '/mkt/rejectOrder',
  statement: '/mkt/statement',
  // operator (MKT_OPERATE / MKT_SETTLE / MKT_SUPPORT)
  matchQueue: '/platform/mkt/matchQueue',
  decideMatch: '/platform/mkt/decideMatch',
  offerQueue: '/platform/mkt/offerQueue',
  decideOffer: '/platform/mkt/decideOffer',
  suspendSeller: '/platform/mkt/suspendSeller',
  requestPayout: '/platform/mkt/requestPayout',
  approvePayout: '/platform/mkt/approvePayout',
  markPayoutPaid: '/platform/mkt/markPayoutPaid',
  supportCases: '/platform/mkt/supportCases',
  taskCase: '/platform/mkt/taskCase',
  // customer (platform-scoped account, R-MKT-5)
  checkout: '/marketplace/checkout',
  myMarketplaceOrders: '/marketplace/myOrders',
  cancelOrder: '/marketplace/cancelOrder',
  requestReturn: '/marketplace/requestReturn',
  openCase: '/marketplace/openCase',
}

/** Screens and selectors (design §6.5). */
const UI = {
  publicPage: '/marketplace',
  search: '#mktSearch',
  productCard: '.mkt-product-card',
  offerCount: '.mkt-offer-count',      // "Available from 2 sellers"
  fromPrice: '.mkt-from-price',        // "From Rs. 51,500"
  sort: '#mktOfferSort',
  offerRow: '.mkt-offer-row',          // carries data-offer-id, data-seller
  chooseOffer: '.mkt-choose-offer',
  buyButton: '#mktBuyBtn',             // names the chosen seller
  checkoutStatus: '#mktCheckoutStatus',
  sellerSection: 'MarketplaceDiv',     // cy.openSection value on /businessDashboard
  proposeBtn: '#mktProposeBtn',
  offersTable: '#mktOffersTable',
  incoming: '#mktIncomingOrders',
  acceptBtn: '.mkt-accept',
  rejectBtn: '.mkt-reject',
  countdown: '.mkt-accept-countdown',
  statementTable: '#mktStatementTable',
  operatorPage: '/platform/marketplace',
}

/** Read either envelope the same way (standard 8c) — never `body.success` alone on a GenericResponse. */
const ok = (body) => !!body && (body.success === true || body.status === 'SUCCESS')
const data = (body) => (body && (body.data !== undefined ? body.data : body.object))
const list = (body) => {
  const d = body && (body.data !== undefined ? body.data : body.collection)
  return Array.isArray(d) ? d : (d && Array.isArray(d.content) ? d.content : [])
}
const msg = (body) => (body && body.message) || ''

/** POST JSON, never failing on status: a refusal is an ANSWER (200 + success:false), asserted by the caller. */
const post = (url, body) => cy.request({
  method: 'POST', url, body, failOnStatusCode: false, headers: { 'Content-Type': 'application/json' },
})
const get = (url) => cy.request({ url, failOnStatusCode: false })

/** Assert a refusal is a polite answer carrying the server's sentence — and prove it is not a 404 (no endpoint). */
const expectRefused = (r, sentencePart) => {
  expect(r.status, 'a refusal is an answer, not a missing endpoint or a crash').to.be.oneOf([200, 400, 403, 409, 422])
  expect(ok(r.body), `refused: ${JSON.stringify(r.body)}`).to.eq(false)
  if (sentencePart) expect(msg(r.body)).to.contain(sentencePart)
}

/**
 * Make `email` an entitled, opted-in seller — the way its owner and the operator would (GATE-RUNBOOK §2).
 * Order matters and is itself asserted elsewhere: the operator entitles, then the owner switches the module on
 * and accepts the seller + data-sharing agreements.
 */
const makeSeller = (email) => {
  cy.loginAsOperator()
  cy.setEntitlement(email, 'marketplaceSelling', 'ACTIVE', 'mkt gate')
  cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')
  cy.setCapability('marketplaceSelling', true)
  return post(API.acceptAgreement, { agreement: 'SELLER_AND_DATA_SHARING', version: 'v1' })
    .then((r) => expect(ok(r.body), `acceptAgreement: ${JSON.stringify(r.body)}`).to.eq(true))
}

/** Seed a catalog product with stock in the CURRENT tenant; returns the product id (asserts the seed took). */
const seedProduct = ({ name, barcode, manufacturer = 'Samsung', price = 52000, qty = 5, rx = false } = {}) =>
  cy.request({
    method: 'POST', url: '/addProduct', failOnStatusCode: false, headers: { 'Content-Type': 'application/json' },
    body: { name: name || `MKT Phone ${uniq()}`, sku: 'MKT' + uniq(), barcode, manufacturer,
      sellingPrice: price, taxRate: 0, unit: 'pcs', rxRequired: rx },
  }).then((r) => {
    const id = r.body && r.body.data && r.body.data.id
    expect(id, `seeded product: ${JSON.stringify(r.body)}`).to.exist
    return cy.request({ method: 'POST', url: '/addProductStock', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' }, body: { productId: id, quantity: qty } })
      .then(() => id)
  })

/** The identity attributes of the source's example phone, with a per-run model suffix so reruns do not collide. */
const a32 = (run, storage = '128GB', colour = 'Black') => ({
  brand: 'Samsung', model: `Galaxy A32 T${run}`, variant: storage, colour, condition: 'New', warrantyType: '12M',
})

module.exports = { gate, uniq, SELLER_A, SELLER_B, OUTSIDER, API, UI, ok, data, list, msg, post, get,
  expectRefused, makeSeller, seedProduct, a32 }
