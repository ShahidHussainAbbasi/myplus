/**
 * EXP-REQ — where the business tracks expiry, a purchase needs an expiry date, unless the product is marked "No expiry".
 * Where it does not track expiry (POS, Mobile Shop), the date may be blank. Design: selling-price-per-purchase-analysis.md
 * §12.18 (owner's rule + the per-product exemption, 2026-10-10).
 *
 * What each case protects:
 *   R1  pharmacy, ordinary product, blank expiry: the SERVER refuses, names the product, and records nothing
 *   R2  …the same purchase with an expiry date saves
 *   R3  pharmacy, product marked "No expiry": blank saves
 *   R4  a business that does not track expiry (owner.business, general): blank saves, as before
 *   R5  the form: the Expiry label is marked required for an ordinary product, and Save with it blank stops on the box
 *       without sending anything; for a "No expiry" product the label is not marked
 *   R6  the product form offers "No expiry date" where expiry is tracked, and not where it is not
 *   R7  a plain user cannot set the exemption (ADMIN-gated write)
 *
 * Tenants: owner.pharma@ (org 15, pharmacy — expiry tracking is the shape's FLOOR), owner.business@ (org 13, general),
 * user.pharma@ for the ladder. Every bill saved is voided; the products are spec-seeded.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/purchase-expiry-required.cy.js --headed --browser chrome
 */
const STAMP = Date.now()
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return [] }
const future = () => { const d = new Date(); d.setFullYear(d.getFullYear() + 2); return d.toISOString().slice(0, 10) }
const INV = (c) => `EXQ-${c}-${STAMP}`

/** A cash purchase (no vendor) of 3 at 50, with or without an expiry. Returns the response body. */
const buy = (productId, inv, expiry) => cy.request({
  method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
  body: Object.assign({ productId, quantity: 3, purchaseRate: 50, 'stock.bpurchaseRate': 50, 'stock.bsellRate': 70,
    'stock.batchNo': inv, totalAmount: 150, netAmount: 60, paidAmount: 150, purchaseInvoiceNo: inv },
    expiry ? { 'stock.bexpDate': expiry } : {}),
}).its('body')
// Yields { bill } — a .then returning undefined would hand the RESPONSE on, so "no bill" could never read as undefined.
const billOf = (inv) => cy.request('/getUserPurchase').then((r) => ({ bill: list(r.body).find((p) => p.purchaseInvoiceNo === inv) }))
// The product as the product FORM loads it (catalog /products/{id}); the grid's /getUserProduct is a slim projection.
const productOf = (id) => cy.request('/getCatalogProduct?id=' + id).then((r) => (r.body && (r.body.data || r.body)) || {})
const setNoExpiry = (id, on) => cy.request({ method: 'POST', url: '/setProductTracking', form: true, failOnStatusCode: false,
  body: { id, noExpiry: on } }).its('body')
const voidAll = () => cy.request('/getUserPurchase').then((r) => list(r.body)
  .filter((p) => String(p.purchaseInvoiceNo).endsWith('-' + STAMP) && p.status !== 'VOID')
  .forEach((p) => cy.request({ method: 'POST', url: '/voidPurchase', form: true, failOnStatusCode: false,
    body: { purchaseId: p.purchaseId, reason: 'EXP-REQ gate cleanup' } })))

let plain = null, exempt = null

describe('EXP-REQ — expiry is required where the business tracks it, unless the product has none', () => {
  before(() => {
    cy.loginAsPharmaOwner()
    cy.seedProduct({ name: 'EXQ_' + uniq(), sellingPrice: 70, noExpiry: false }).then((p) => { plain = p.productId })
    cy.seedProduct({ name: 'EXQN_' + uniq(), sellingPrice: 70 }).then((p) => {
      exempt = p.productId
      setNoExpiry(exempt, true).then((b) => expect(b.success, 'exemption set: ' + JSON.stringify(b).slice(0, 160)).to.not.eq(false))
    })
  })
  beforeEach(() => cy.loginAsPharmaOwner())
  after(() => {
    cy.loginAsPharmaOwner(); voidAll()
    cy.loginAsOwner(); voidAll()
  })

  it('R1 pharmacy, ordinary product, blank expiry: refused by the server, nothing recorded', () => {
    buy(plain, INV('R1')).then((b) => {
      expect(b.status, JSON.stringify(b)).to.not.eq('SUCCESS')
      expect(b.message).to.contain('expiry date').and.to.contain('EXQ_')
    })
    billOf(INV('R1')).then(({ bill }) => expect(bill, 'no bill was recorded').to.be.undefined)
  })

  it('R2 the same purchase with an expiry date saves', () => {
    buy(plain, INV('R2'), future()).then((b) => expect(b.status, JSON.stringify(b)).to.eq('SUCCESS'))
  })

  it('R3 pharmacy, product marked "No expiry": blank saves', () => {
    productOf(exempt).then((p) => expect(p.noExpiry, 'the product form sees the exemption').to.eq(true))
    buy(exempt, INV('R3')).then((b) => expect(b.status, JSON.stringify(b)).to.eq('SUCCESS'))
  })

  it('R4 a business that does not track expiry: blank saves, as before', () => {
    cy.loginAsOwner()
    cy.seedProduct({ name: 'EXQR_' + uniq(), sellingPrice: 70 }).then(({ productId }) =>
      buy(productId, INV('R4')).then((b) => expect(b.status, JSON.stringify(b)).to.eq('SUCCESS')))
  })

  it('R5 the form marks Expiry required for an ordinary product, stops a blank save, and not for a "No expiry" one', () => {
    cy.openPurchaseSection('purchaseDiv')
    cy.get('#newPurchase').click()
    cy.get('#PurchaseModal').should('have.class', 'open')
    cy.settled('#purchaseInvoiceNo')
    cy.get('#purchaseInvoiceNo').clear().type(INV('R5'))
    cy.intercept('GET', '/productStock*').as('prefill')
    cy.get('#purchaseItemDD').select(String(plain), { force: true })
    cy.wait('@prefill', { timeout: 15000 })
    cy.get('label[for="purchaseExpiry"]').should('have.class', 'req')
    cy.get('#purchaseQuantity').clear().type('3')
    cy.get('#purchasePurchaseRate').clear().type('50')
    cy.get('#purchaseExpiry').should('have.value', '')
    cy.intercept('POST', '**/addPurchase').as('save')
    cy.get('#addPurchase').click()
    cy.get('#purchaseExpiry').should(($e) => expect($e[0].style.borderColor, 'the box is flagged').to.eq('red'))
    cy.focused().should('have.id', 'purchaseExpiry')
    cy.wait(800)
    cy.get('@save.all').should('have.length', 0)   // nothing was sent
    cy.get('#purchaseItemDD').select(String(exempt), { force: true })
    cy.wait('@prefill', { timeout: 15000 })
    cy.get('label[for="purchaseExpiry"]').should('not.have.class', 'req')
    cy.get('#PurchaseModal .crud-x').first().click({ force: true })
    billOf(INV('R5')).then(({ bill }) => expect(bill, 'nothing saved from the form').to.be.undefined)
  })

  it('R6 the product form offers "No expiry date" where expiry is tracked, and not where it is not', () => {
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'hasCapability')
    cy.get('#prodNoExpiry').closest('[data-capability]').should('have.attr', 'data-capability', 'expiryTracking')
    cy.window().then((w) => expect(w.hasCapability('expiryTracking'), 'pharmacy tracks expiry').to.eq(true))
    cy.loginAsMobileOwner()
    cy.visit('/businessDashboard')
    cy.window().should('have.property', 'hasCapability')
    cy.window().then((w) => expect(w.hasCapability('expiryTracking'), 'mobile shop does not').to.eq(false))
    cy.get('#prodNoExpiry').closest('[data-capability]').should('not.be.visible')
  })

  it('R7 a plain user cannot set the exemption', () => {
    cy.loginAsTier('user', 'pharma')
    setNoExpiry(plain, true).then((b) => expect(b.success, JSON.stringify(b).slice(0, 160)).to.eq(false))
    cy.loginAsPharmaOwner()
    productOf(plain).then((p) => expect(p.noExpiry, 'still not exempt').to.eq(false))
  })
})
