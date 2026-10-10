/**
 * STK-ALERT — the header badge: expired, expiring and low stock, for owners and admins. Design:
 * microservices/docs/selling-price-per-purchase-analysis.md §12.11 (decisions: pulse then steady; low = the product's own
 * minimum, else the business cap in Configuration → Stock alerts; expired + expiring within N days; owner/admin only).
 *
 * What each case protects:
 *   H1  owner.business@, cap set: the Low badge appears, pulses, and the panel names the products; cap reset → gone
 *   H2  user.business@: no badge at all, and the server refuses the summary
 *   H3  owner.pharma@ (tracks expiry): a batch bought already expired shows under Expired, by name
 *
 * Server state: the cap is reset and the bill voided in the case (and again in after()).
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/header-stock-alerts.cy.js --headed --browser chrome
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return [] }
const CAP = 'pos.stock.lowStockAt'
const setCfg = (key, value) => cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value: String(value) } })
  .then((r) => expect(r.body && r.body.success, `save ${key}: ${JSON.stringify(r.body)}`).to.eq(true))
const resetCfg = (key) => cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key }, failOnStatusCode: false })
const summary = () => cy.request('/stockAlertSummary').its('body')

const openDashboard = () => {
  cy.intercept('GET', '**/stockAlertSummary').as('sa')
  cy.visit('/businessDashboard')
  cy.wait('@sa', { timeout: 30000 })
}

describe('STK-ALERT — the header stock badge', () => {
  after(() => { cy.loginAsOwner(); resetCfg(CAP) })

  it('H1 owner with a cap: the Low badge appears, pulses, names the products; reset → gone', () => {
    cy.loginAsOwner()
    resetCfg(CAP)
    cy.seedProduct({ name: 'SAL_' + uniq(), sellingPrice: 10, stock: 2, purchasePrice: 5 }).then(({ name }) => {
      setCfg(CAP, 999999)                                  // everything with stock rows is "low" — deterministic
      summary().then((s) => expect(Number(s.low), 'the server counts low stock under the cap').to.be.greaterThan(0))
      openDashboard()
      cy.get('#stockAlertNav').should('be.visible')
      cy.get('#stockAlertNav [data-sa="low"]').should('be.visible').invoke('text').should('match', /\d/)
      cy.get('#stockAlertBtn').should('have.class', 'sa-pulse')
      cy.get('#stockAlertBtn').click()
      cy.get('#stockAlertPanel').should('be.visible').find('li').should('have.length.greaterThan', 0)
      cy.get('#stockAlertPanel').should('contain', name.slice(0, 4))   // names, not ids

      resetCfg(CAP)
      summary().then((s) => expect(Number(s.lowAt), 'cap back to off').to.eq(0))
      openDashboard()
      cy.get('#stockAlertNav [data-sa="low"]').should('not.be.visible')
    })
  })

  it('H2 a plain user: no badge, and the server refuses', () => {
    cy.loginAsTier('user', 'business')
    cy.visit('/businessDashboard')
    cy.get('#stockAlertNav').should('not.exist')
    cy.request({ url: '/stockAlertSummary', failOnStatusCode: false }).then((r) =>
      expect(r.body && r.body.success, 'refused for a user: ' + JSON.stringify(r.body).slice(0, 200)).to.not.eq(true))
  })

  it('H3 pharmacy: a batch bought already expired shows under Expired, by name', () => {
    cy.loginAsPharmaOwner()
    const stamp = uniq(), inv = 'SAX-' + stamp, pname = 'SAX_' + stamp
    const y = new Date(Date.now() - 24 * 3600 * 1000)
    const yesterday = `${y.getFullYear()}-${String(y.getMonth() + 1).padStart(2, '0')}-${String(y.getDate()).padStart(2, '0')}`
    cy.ensureCompany().then((companyId) => {
      const vName = 'SAXV_' + stamp
      cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
        body: { name: vName, companyId, mobile: '0306' + String(stamp).slice(-7), email: `sax${stamp}@t.com` } })
      cy.request('/getUserVenders').then((vr) => {
        const html = String(vr.body && vr.body.object != null ? vr.body.object : vr.body)
        const venderId = (new RegExp('<option value=(\\d+)[^>]*>' + vName).exec(html) || [])[1]
        cy.seedProduct({ name: pname, sellingPrice: 30 }).then(({ productId }) => {
          summary().then((before) => {
            cy.request({ method: 'POST', url: '/addPurchase', form: true,
              body: { productId, venderId, quantity: 4, purchaseRate: 20, 'stock.bpurchaseRate': 20, 'stock.bsellRate': 30,
                      'stock.batchNo': inv, 'stock.bexpDate': yesterday, totalAmount: 80, netAmount: 40, purchaseInvoiceNo: inv } })
              .its('body.status').should('eq', 'SUCCESS')
            summary().then((s) => {
              expect(s.trackExpiry, 'the pharmacy tracks expiry').to.eq(true)
              expect(Number(s.expired), 'one more expired batch').to.eq(Number(before.expired) + 1)
            })
            openDashboard()
            cy.get('#stockAlertNav [data-sa="expired"]').should('be.visible')
            cy.get('#stockAlertBtn').click()
            // the panel lists the first 10 by expiry — ours (yesterday) is listed unless 10 older ones exist
            cy.get('#stockAlertPanel').should('be.visible').invoke('text').then((t) => {
              if (Number(before.expired) < 10) expect(t, 'named in the panel').to.contain(pname)
            })
            cy.request('/getUserPurchase').then((r) => {
              const p = list(r.body).find((x) => x.purchaseInvoiceNo === inv)
              cy.request({ method: 'POST', url: '/voidPurchase', form: true, body: { purchaseId: p.purchaseId, reason: 'STK-ALERT gate' } })
                .its('body.status').should('eq', 'SUCCESS')
            })
          })
        })
      })
    })
  })
})
