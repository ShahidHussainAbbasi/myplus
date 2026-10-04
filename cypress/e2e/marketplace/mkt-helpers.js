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

const enabled = () => {
  const v = Cypress.env('mkt')
  // --env values are parsed as JSON: "1e2" arrives as the NUMBER 100 (scientific notation), and the 1e2 gate then
  // skipped every case as "pending" — a silent no-op that reads like a pass. Refuse it loudly instead.
  if (typeof v === 'number') {
    throw new Error(`--env mkt=${v} arrived as a number. Pass it as JSON (--env '{"mkt":"1e2"}') or use mkt=all.`)
  }
  return String(v || '').split(',').map((s) => s.trim()).filter(Boolean)
}

/** `describe` when this slice is switched on for the run, `describe.skip` (reported as pending) otherwise. */
const gate = (slice) => (enabled().includes('all') || enabled().includes(slice) ? describe : describe.skip)

const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

/**
 * Who plays which part (GATE-RUNBOOK §1: log in as the tenant the feature belongs to).
 *   SELLER_A  owner.business@  — a retail counter: the tier ladder (admin./user.) lives in this org
 *   SELLER_B  owner.mobile@    — a mobile shop: the second seller of the SAME phone, the "2 sellers" case
 *   OUTSIDER  owner.pesticide@ — a business tenant no MKT gate ever enrols: the not-entitled control
 *   PHARMACY  owner.pharma@    — the seeded pharmacy (prescription control ON): the regulated-product seller
 *   OPERATOR  admin@myplus.com — MaxTheService staff (ROLE_ADMIN), not a tenant
 */
const SELLER_A = 'owner.business@myplus.com'
const SELLER_B = 'owner.mobile@myplus.com'
const OUTSIDER = 'owner.pesticide@myplus.com'
/**
 * PHARMACY — the seeded pharmacy, with prescription control ON. The ONLY kind of tenant that can own a prescription
 * product: catalog refuses the rx flag to a tenant without that capability (catalog C6), so a retail seller like
 * SELLER_A can never hold one. Used by the "regulated products are refused" cases. (demo.pharma@ owns no org here.)
 */
const PHARMACY = 'owner.pharma@myplus.com'

/** Monolith flat routes (ARCHITECTURE-MULTITENANCY §1) — each proxies /api/marketplace/mkt/** and relays the message. */
const API = {
  // public (anonymous)
  publicProducts: '/marketplace/public/products',            // MKT-1d: ?q=&city=&page=&size≤24 → cards
  publicProduct: (id) => `/marketplace/public/products/${id}`, // MKT-1d: product + defaultSort + sorts
  publicOffers: (id) => `/marketplace/public/products/${id}/offers`, // ?sort=&city=&qty=
  // seller (MKT_SELL + capability marketplaceSelling + operator entitlement)
  sellerStatus: '/mkt/seller',                                // MKT-0a: capability, agreements, account, canSell
  acceptAgreement: '/mkt/acceptAgreement',                    // MKT-0a: {version, displayName}
  proposeProduct: '/mkt/proposeProduct',                      // MKT-1b: {sourceProductId, brand, model, variant, …}
  myProposals: '/mkt/myProposals',
  saveOffer: '/mkt/saveOffer',                                // MKT-1c: {id?, mktProductId, marketplacePrice, deliveryAreas, …}
  submitOffer: '/mkt/submitOffer',                            // MKT-1c: {id} → PENDING_REVIEW
  myOffers: '/mkt/myOffers',
  getOffer: (id) => `/mkt/getOffer?id=${id}`,
  sellerPolicies: '/mkt/sellerPolicies',                      // MKT-1c: active WARRANTY + RETURN policies
  incomingOrders: '/mkt/incomingOrders',                      // MKT-1e: ?status=OFFERED|ACCEPTED|…
  acceptOrder: '/mkt/acceptOrder',                            // MKT-1e: {id, version, serials: []}
  rejectOrder: '/mkt/rejectOrder',                            // MKT-1e: {id, version, reason}
  statement: '/mkt/statement',                              // MKT-1g: ?status=&size= → lines with the whole split
  // operator (MKT_OPERATE / MKT_SETTLE / MKT_SUPPORT)
  matchQueue: '/platform/mkt/matchQueue',                    // MKT-1b: ?status=PENDING_REVIEW|MATCHED|NEEDS_CORRECTION
  decideMatch: '/platform/mkt/decideMatch',                  // MKT-1b: {id, decision, mktProductId, note, version}
  mktProducts: '/platform/mkt/products',                      // MKT-1b: canonical products ?q=
  offerQueue: '/platform/mkt/offerQueue',                    // MKT-1c: ?status=PENDING_REVIEW|APPROVED|…
  decideOffer: '/platform/mkt/decideOffer',                  // MKT-1c: {id, decision: APPROVE|REJECT|SUSPEND|REINSTATE, note, version}
  policies: '/platform/mkt/policies',
  createPolicy: '/platform/mkt/createPolicy',                // MKT-1c: {policyType, name, …} — never edited
  deactivatePolicy: '/platform/mkt/deactivatePolicy',        // MKT-1c: {id}
  productLimits: '/platform/mkt/productLimits',              // MKT-1c: {id, priceFloor, priceCeiling}
  defaultSort: '/platform/mkt/defaultSort',                  // MKT-1d: GET → {sort}; POST {sort}
  sellers: '/platform/mkt/sellers',                           // MKT-0a: ?status=PENDING_APPROVAL|APPROVED|…
  decideSeller: '/platform/mkt/decideSeller',                 // MKT-0a: {organizationId, decision, reason, version}
  requestPayout: '/platform/mkt/requestPayout',            // MKT-1g: {organizationId, idempotencyKey}
  approvePayout: '/platform/mkt/approvePayout',            // MKT-1g: {id} — never by its requester
  markPayoutPaid: '/platform/mkt/markPayoutPaid',          // MKT-1g: {id, bankReference}
  supportCases: '/platform/mkt/supportCases',
  taskCase: '/platform/mkt/taskCase',
  // customer (platform-scoped account, R-MKT-5)
  checkout: '/marketplace/public/checkout',                   // MKT-1e: anonymous, CSRF token required
  trackOrder: (no, phone) => `/marketplace/public/orders/${encodeURIComponent(no)}?phone=${encodeURIComponent(phone)}`,
  operatorOrders: '/platform/mkt/orders',                     // MKT-1e: ?status=
  acceptWindow: '/platform/mkt/acceptWindow',                 // MKT-1e: GET → {minutes}; POST {minutes}
  myMarketplaceOrders: '/marketplace/myOrders',
  cancelOrder: '/marketplace/cancelOrder',
  requestReturn: '/marketplace/requestReturn',
  openCase: '/marketplace/openCase',
}

/** Screens and selectors (design §6.5). */
const UI = {
  publicPage: '/marketplace',
  search: '#mktSearch',
  city: '#mktCity',
  productCard: '.mkt-product-card',
  offerCount: '.mkt-offer-count',      // "Available from 2 sellers"
  fromPrice: '.mkt-from-price',        // "From Rs. 51,500"
  sort: '#mktOfferSort',
  offerRow: '.mkt-offer-row',          // carries data-offer-id, data-seller
  chooseOffer: '.mkt-choose-offer',   // a real radio button per row
  buyButton: '#mktBuyBtn',             // names the chosen seller
  checkoutStatus: '#mktCheckoutStatus',
  sellerSection: '#MarketplaceDiv',    // MKT-0a: Sale → Marketplace (#navMarketplaceSeller) on /businessDashboard
  sellerNav: '#navMarketplaceSeller',
  proposeBtn: '#mktProposeBtn',
  offersTable: '#mktOffersTable',
  incoming: '#mktIncomingOrders',
  acceptBtn: '.mkt-accept',
  rejectBtn: '.mkt-reject',
  countdown: '.mkt-accept-countdown',
  statementTable: '#mktStatementTable',
  operatorPage: '/platformDashboard',  // MKT-0a: "Marketplace sellers" (#platMktSellersBtn → #platMktSellers)
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
const makeSeller = (email, displayName = 'MKT gate seller') => {
  cy.loginAsOperator()
  cy.setEntitlement(email, 'marketplaceSelling', 'ACTIVE', 'mkt gate')
  cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')
  cy.setCapability('marketplaceSelling', true)
  // the capability travels in the JWT: log in again so the token carries it (operator doc caveat 1)
  cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats', 'mkt-on')
  post(API.acceptAgreement, { version: 'v1', displayName })
    .then((r) => expect(ok(r.body), `acceptAgreement: ${JSON.stringify(r.body)}`).to.eq(true))
  cy.loginAsOperator()
  cy.orgOf(email).then((org) => post(API.decideSeller, { organizationId: org.id, decision: 'APPROVE' }))
    .then((r) => {
      // APPROVED already (an earlier run) is a refused move, and is fine; anything else must have worked
      if (!ok(r.body)) expect(msg(r.body)).to.match(/approved cannot be moved/)
    })
  return cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats', 'mkt-on')
}

/** Open the seller screen the way a person does: Sale → Marketplace. */
const openMarketplace = () => {
  cy.visit('/businessDashboard')
  cy.get('#snavSell .snav-btn').click()
  cy.get(UI.sellerNav).should('be.visible').click()
  return cy.get(UI.sellerSection).should('be.visible')
}

/** Seed a catalog product with stock in the CURRENT tenant; returns the product id (asserts the seed took). */
const seedProduct = ({ name, barcode, manufacturer = 'Samsung', price = 52000, qty = 5 } = {}) =>
  cy.request({
    method: 'POST', url: '/addProduct', failOnStatusCode: false, headers: { 'Content-Type': 'application/json' },
    body: { name: name || `MKT Phone ${uniq()}`, sku: 'MKT' + uniq(), barcode, manufacturer,
      sellingPrice: price, taxRate: 0, unit: 'pcs' },
  }).then((r) => {
    const id = r.body && r.body.data && r.body.data.id
    expect(id, `seeded product: ${JSON.stringify(r.body)}`).to.exist
    return cy.request({ method: 'POST', url: '/addProductStock', failOnStatusCode: false,
      headers: { 'Content-Type': 'application/json' }, body: { productId: id, quantity: qty } })
      .then(() => id)
  })

/**
 * A product the CATALOG marks prescription-only, in the current (pharmacy) tenant — through the pharmacy's own
 * Clinical & Safety save, as a pharmacist would. Asserts the flag took, so a refusal later is about the rule.
 */
const seedRxProduct = (name) => seedProduct({ name, manufacturer: 'GSK', price: 300 }).then((id) =>
  post('/saveClinical', { productId: id, medicineName: name, rxRequired: true, controlledSubstance: false }).then((r) => {
    expect(ok(r.body), `rx flag saved: ${JSON.stringify(r.body)}`).to.eq(true)
    return id
  }))

/** The identity attributes of the source's example phone, with a per-run model suffix so reruns do not collide. */
const a32 = (run, storage = '128GB', colour = 'Black') => ({
  brand: 'Samsung', model: `Galaxy A32 T${run}`, variant: storage, colour, condition: 'New', warrantyType: '12M',
})

/**
 * Operator policies a published offer needs: a warranty and a return policy, plus a default commission when none is
 * active (creating a default replaces the platform-wide one, so it is never done needlessly). Yields their ids.
 */
const seedPolicies = (run, { months = 12, provider = 'Samsung Pakistan', returnDays = 7 } = {}) => {
  const ids = {}
  cy.loginAsOperator()
  post(API.createPolicy, { policyType: 'WARRANTY', name: `${months} months ${run}`, warrantyProvider: provider, warrantyMonths: months })
    .then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); ids.warranty = data(r.body).id })
  post(API.createPolicy, { policyType: 'RETURN', name: `${returnDays} days ${run}`, returnDays })
    .then((r) => { expect(ok(r.body), JSON.stringify(r.body)).to.eq(true); ids.returns = data(r.body).id })
  get(API.policies).then((r) => {
    if (!list(r.body).some((p) => p.policyType === 'COMMISSION' && p.active && p.isDefault)) {
      post(API.createPolicy, { policyType: 'COMMISSION', name: `Standard ${run}`, isDefault: true, commissionBasis: 'ITEMS', commissionRate: 0.08 })
    }
  })
  return cy.wrap(ids)
}

/**
 * The whole publishing path, the way the seller and the operator do it: seed a catalog product with stock → propose
 * it → operator matches it (to `mktProductId` when given, so two sellers share ONE canonical product) → seller
 * offers it → submits → operator approves. Yields {offerId, mktProductId, sourceProductId}.
 */
const publishOffer = (email, { run, price, promiseHours = 24, qty = 5, areas = 'Karachi', warrantyPolicyId, returnPolicyId,
  mktProductId } = {}) => {
  const out = {}
  makeSeller(email)
  return seedProduct({ name: `${a32(run).model} ${email}`, qty }).then((pid) => {
    out.sourceProductId = pid
    return post(API.proposeProduct, { sourceProductId: pid, ...a32(run) })
  }).then((r) => {
    expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
    const prop = data(r.body)
    cy.loginAsOperator()
    return post(API.decideMatch, { id: prop.id, decision: 'MATCHED', mktProductId, version: prop.version })
  }).then((d) => {
    expect(ok(d.body), JSON.stringify(d.body)).to.eq(true)
    out.mktProductId = data(d.body).mktProductId
    cy.loginAs(email, 'Demo@2025!', '/getBusinessDashboardStats')
    return post(API.saveOffer, { mktProductId: out.mktProductId, marketplacePrice: price, deliveryAreas: areas, promiseHours,
      warrantyPolicyId, returnPolicyId })
  }).then((r) => {
    expect(ok(r.body), JSON.stringify(r.body)).to.eq(true)
    out.offerId = data(r.body).id
    return post(API.submitOffer, { id: out.offerId })
  }).then((s) => {
    expect(ok(s.body), JSON.stringify(s.body)).to.eq(true)
    cy.loginAsOperator()
    return post(API.decideOffer, { id: out.offerId, decision: 'APPROVE' })
  }).then((a) => {
    expect(ok(a.body), JSON.stringify(a.body)).to.eq(true)
    return out
  })
}

module.exports = { seedPolicies, publishOffer, seedRxProduct, PHARMACY, gate, uniq, SELLER_A, SELLER_B, OUTSIDER, API, UI, ok, data, list, msg, post, get,
  expectRefused, makeSeller, openMarketplace, seedProduct, a32 }
