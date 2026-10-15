/**
 * EXP-ENTRY — the purchase Expiry box starts EMPTY, and an expiry of today or earlier is asked about before it is saved.
 *
 * Found 2026-10-10 (doc selling-price-per-purchase-analysis.md §12.10): main.js initDates() filled every empty date box
 * with today, the purchase expiry included — 565 of 579 bills with an expiry carried the bill's own date. Sold
 * first-expiry-first, such a batch is the first one taken, and it counts as expired from the next day.
 *
 * What each case protects:
 *   X1  New Purchase, product picked: the Expiry box is still empty. Picking a product is what filled it — every
 *       `.onChangeSelect` (the product picker among them) runs initDates() on change
 *   X2  expiry = today → the save stops and asks; Cancel sends nothing and leaves the operator on the box
 *   X3  …and "Save anyway" saves it (stock can arrive already expired, e.g. to return to the supplier)
 *   X4  a future expiry saves without a question
 *
 * Tenant: owner.pharma@ (expiry tracking on, so the box is shown). Every bill saved is voided in the case.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/purchase-expiry-entry.cy.js --headed --browser chrome
 */
const STAMP = Date.now()
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return [] }
const today = () => Cypress.dayjs ? Cypress.dayjs().format('DD-MM-YYYY') : new Date().toLocaleDateString('en-GB').replace(/\//g, '-')

let vendorId = null, productId = null
const vname = 'EXPV_' + STAMP

const openNewPurchase = () => {
  cy.openPurchaseSection('purchaseDiv')
  cy.get('#newPurchase').click()
  cy.get('#PurchaseModal').should('have.class', 'open')
  cy.settled('#purchaseInvoiceNo')
}
const fillLine = (inv) => {
  cy.get('#purchaseVenderDD').select(String(vendorId), { force: true })
  cy.get('#purchaseInvoiceNo').clear().type(inv)
  cy.intercept('GET', '/productStock*').as('prefill')
  cy.get('#purchaseItemDD').select(String(productId), { force: true })
  cy.wait('@prefill', { timeout: 15000 })
  cy.get('#purchaseQuantity').clear().type('3')
  cy.get('#purchasePurchaseRate').clear().type('50')
}
const voidBill = (inv) => cy.request('/getUserPurchase').then((r) => {
  const p = list(r.body).find((x) => x.purchaseInvoiceNo === inv)
  expect(p, 'the saved bill ' + inv).to.exist
  cy.request({ method: 'POST', url: '/voidPurchase', form: true, body: { purchaseId: p.purchaseId, reason: 'EXP-ENTRY gate' } })
    .its('body.status').should('eq', 'SUCCESS')
})

describe('EXP-ENTRY — the purchase expiry is never invented, and a past one is asked about', () => {
  before(() => {
    cy.loginAsPharmaOwner()
    cy.ensureCompany().then((companyId) => {
      cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
        body: { name: vname, companyId, mobile: '0305' + String(STAMP).slice(-7), email: 'expv' + STAMP + '@t.com' } })
      cy.request('/getUserVenders').then((vr) => {
        const html = String(vr.body && vr.body.object != null ? vr.body.object : vr.body)
        vendorId = (new RegExp('<option value=(\\d+)[^>]*>' + vname).exec(html) || [])[1]
        expect(vendorId, 'spec vendor').to.exist
      })
    })
    cy.seedProduct({ name: 'EXPP_' + uniq(), sellingPrice: 80 }).then((p) => { productId = p.productId })
  })
  beforeEach(() => cy.loginAsPharmaOwner())
  // Leave no bill behind even when a case fails before its own void (seen: on the old build X2 saved without asking).
  after(() => {
    cy.loginAsPharmaOwner()
    cy.request('/getUserPurchase').then((r) => list(r.body)
      .filter((p) => String(p.purchaseInvoiceNo).startsWith('EXP-') && String(p.purchaseInvoiceNo).includes(String(STAMP).slice(0, 6)) && p.status !== 'VOID')
      .forEach((p) => cy.request({ method: 'POST', url: '/voidPurchase', form: true, failOnStatusCode: false,
        body: { purchaseId: p.purchaseId, reason: 'EXP-ENTRY gate cleanup' } })))
  })

  it('X1 New Purchase, product picked: the Expiry box is still empty', () => {
    openNewPurchase()
    cy.get('#purchaseExpiry').should('be.visible').and('have.value', '')
    fillLine('EXP-X1-' + uniq())                       // the product pick ran initDates()
    cy.wait(500)
    cy.get('#purchaseExpiry').should('have.value', '')
    cy.get('#PurchaseModal .crud-x').first().click({ force: true })   // nothing saved
  })

  it('X2 + X3 expiry today: the save asks; Cancel sends nothing; "Save anyway" saves', () => {
    const inv = 'EXP-T-' + uniq()
    openNewPurchase()
    fillLine(inv)
    cy.get('#purchaseExpiry').invoke('val', today()).trigger('change')
    let posted = 0
    cy.intercept('POST', '**/addPurchase', () => { posted++ }).as('save')
    cy.get('#addPurchase').click()
    cy.get('[data-ui-confirm="ok"]').should('be.visible')
    cy.get('[role="dialog"]').should('contain', today())
    cy.get('.uiC-cancel').click()
    cy.wait(800).then(() => expect(posted, 'Cancel sends nothing').to.eq(0))
    cy.focused().should('have.id', 'purchaseExpiry')

    cy.get('#addPurchase').click()
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@save').its('response.body.status').should('eq', 'SUCCESS')
    voidBill(inv)
  })

  it('X4 a future expiry saves without a question', () => {
    const inv = 'EXP-F-' + uniq()
    openNewPurchase()
    fillLine(inv)
    cy.get('#purchaseExpiry').invoke('val', '31-12-2030').trigger('change')
    cy.intercept('POST', '**/addPurchase').as('save')
    cy.get('#addPurchase').click()
    cy.wait('@save').its('response.body.status').should('eq', 'SUCCESS')
    cy.get('[data-ui-confirm="ok"]').should('not.exist')
    voidBill(inv)
  })
})
