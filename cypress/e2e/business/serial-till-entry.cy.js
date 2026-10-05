/**
 * SER-3b on the TILL — the gate the Test Book recorded as missing (§1, §7, §13: "Serial / IMEI entry changed shape
 * and has no automated gate yet"). Verify sweep, 2026-10-05.
 *
 * What a cashier at a mobile shop does, and what must hold:
 *   - the Serial / IMEI box sits BEFORE QTY, wide enough to read a 15-digit IMEI back against the handset;
 *   - a serial makes QTY 1 and READONLY (not disabled — a disabled field is dropped on submit, and the sale would be
 *     recorded for no quantity); clearing the serial gives QTY back;
 *   - after Add to Cart the NEXT line is unlocked (resetForm empties the box without an input event);
 *   - the completed sale carries quantity 1 and the IMEI;
 *   - Configuration → "Show the Serial / IMEI field" off hides the box on the till (goods-in is gated by
 *     serial-register-fixes.cy.js), and a shop without serial tracking never sees it (GATE-RUNBOOK rule 3).
 *
 * Tenant: owner.mobile@ (retail + serialTracking, established in before — never inherited). Cross-tenant:
 * owner.business@ (retail, no serial tracking).
 */
const uniq = () => Date.now().toString().slice(-8) + Math.floor(Math.random() * 900 + 100)
const SHOW = 'pos.entry.showSerial'

/** A serial-tracked product with ONE unit in the register, received under `imei`. */
const trackedInStock = (imei) => cy.seedProduct({ name: `SERTILL_${uniq()}`, sellingPrice: 500, stock: 0 })
  .then(({ productId }) => cy.request({ method: 'POST', url: '/setProductTracking', form: true,
    body: { id: productId, requiresSerial: 'true' }, failOnStatusCode: false })
    .then((r) => {
      expect(JSON.stringify(r.body), `product ${productId} is serial-tracked`).to.not.match(/error/i)
      return cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
        body: { productId, quantity: 1, serials: imei, purchaseRate: 300, 'stock.bpurchaseRate': 300,
                'stock.bsellRate': 500, totalAmount: 300, netAmount: 300, paidAmount: 300,
                purchaseInvoiceNo: 'SERTILL-' + uniq() } })
    })
    .then((r) => {
      expect(r.body && r.body.status, `the handset is received: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
      return cy.wrap(productId)
    }))

const openSale = () => {
  cy.visitDashboardSettled()
  cy.get('#sellType').select('sellDiv', { force: true })
  cy.get('#sellItemDD', { timeout: 20000 }).should('exist')
}
const serialCell = () => cy.get('#sellDiv [data-pos-field="serial"]')
const configValue = (key) => cy.request('/getBusinessConfig').then((r) => {
  const rows = (r.body && (r.body.data || r.body.collection)) || []
  return rows.find((e) => e.key === key) || null
})

describe('SER-3b — Serial / IMEI at the till', () => {
  let showWas = null          // the tenant's OWN override, restored exactly (null = none: reset)
  let planWas = null          // { id, plan } — lifted to PRO only if the seed left it on FREE, and put back
  let bizCapWas = null        // owner.business@'s own serialTracking override, restored exactly in after()

  before(() => {
    /*
     * ⚠ A FRESH seed puts owner.mobile@ on FREE, which does not include serial tracking: SetupDataLoader writes the
     * capability override directly, so it READS on, but re-asserting it below is refused at the plan ceiling ("not
     * included in your current plan"). Lifted with the reversible plan swap (cy.setPlan), never an entitlement row.
     */
    cy.loginAsOperator()
    cy.planOf('owner.mobile@myplus.com').then((p) => {
      planWas = p
      if (p.plan === 'FREE') cy.setPlan(p.id, 'PRO')
    })
    cy.loginAsMobileOwner()
    cy.setShape('retail')
    cy.setCapability('serialTracking', true)
    configValue(SHOW).then((row) => { showWas = row && row.isDefault === false ? String(row.value) : null })
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: SHOW } })
  })

  beforeEach(() => cy.loginAsMobileOwner())

  after(() => {
    if (bizCapWas) {
      const KEY = 'org.cap.serialTracking'
      cy.loginAsOwner()
      if (bizCapWas.value === null) cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY } })
      else cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key: KEY, value: bizCapWas.value } })
    }
    if (planWas && planWas.plan === 'FREE') { cy.loginAsOperator(); cy.setPlan(planWas.id, 'FREE') }
    cy.loginAsMobileOwner()
    if (showWas === null) cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: SHOW } })
    else cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key: SHOW, value: showWas } })
  })

  it('⭐ a serial locks QTY to 1, the next line is free again, and the sale records ONE unit', () => {
    const imei = '35' + uniq().padEnd(13, '7').slice(0, 13)        // 15 digits, like a real IMEI
    const buyer = `SerTill Buyer ${uniq()}`
    cy.intercept('POST', '**/addSell').as('sale')

    trackedInStock(imei).then((productId) => {
      openSale()
      cy.get('#sellItemDD').select(String(productId), { force: true })

      // The box is on the line, visible and BEFORE QTY.
      cy.get('#sellSerials').should('be.visible')
      cy.get('#sellSerials').then(($s) => cy.get('#sellQuantity').then(($q) => {
        expect($s[0].compareDocumentPosition($q[0]) & Node.DOCUMENT_POSITION_FOLLOWING,
          'Serial comes before QTY in the line').to.be.greaterThan(0)
      }))

      // A quantity typed first is overruled by the serial — one serial is one unit.
      cy.get('#sellQuantity').clear().type('3')
      cy.get('#sellSerials').clear().type(imei)
      cy.get('#sellQuantity').should('have.value', '1').and('have.attr', 'readonly')
      cy.get('#sellQuantity').should('not.be.disabled')       // readonly, never disabled: it must still submit

      // The whole IMEI is readable in its box — nothing scrolled out of sight.
      cy.get('#sellSerials').should(($s) => {
        expect($s[0].scrollWidth, 'a 15-digit IMEI fits its box').to.be.at.most($s[0].clientWidth + 1)
      })

      // Clearing the serial gives QTY back.
      cy.get('#sellSerials').clear()
      cy.get('#sellQuantity').should('not.have.attr', 'readonly')
      cy.get('#sellQuantity').clear().type('2').should('have.value', '2')

      // Serial again, then Add to Cart.
      cy.get('#sellSerials').type(imei)
      cy.get('#sellQuantity').should('have.value', '1').and('have.attr', 'readonly')
      /*
       * ⚠ THE LINE IS PRICED FOR ONE. Found by this gate (verify sweep, 2026-10-05): the lock wrote QTY = 1 with
       * .val(), which fires none of QTY's own handlers, so the line total kept the 2 × 500 typed a moment earlier —
       * the cart read "1 × 500 = 1000.00" and the customer would have been charged for two handsets.
       */
      cy.get('#sellTotalAmount').invoke('val').then((v) => expect(Number(v), 'the line total is ONE unit at 500').to.eq(500))
      cy.get('#addInviceItem').click({ force: true })          // sic: the app's id
      cy.window({ timeout: 15000 }).its('data').should('have.length', 1)

      // ⚠ The NEXT line: the box is empty and QTY is NOT stranded at a locked 1.
      cy.get('#sellSerials').should('have.value', '')
      cy.get('#sellQuantity').should('not.have.attr', 'readonly')

      // Complete it as an ordinary cash sale to a named walk-in.
      cy.get('#btnModeManual').click({ force: true })
      cy.get('#sellCN').should('be.visible').clear().type(buyer)
      cy.get('#sellRec').clear().type('500')
      cy.get('#addSell').click({ force: true })
      cy.confirmSale({ optional: true })
      cy.wait('@sale', { timeout: 30000 }).then((i) => {
        expect(i.response.body.status, JSON.stringify(i.response.body).slice(0, 300)).to.eq('SUCCESS')
        const lines = i.request.body.sales || []
        const line = lines.find((l) => String(l.serials || '').includes(imei))
        expect(line, `the line carries the IMEI: ${JSON.stringify(lines)}`).to.exist
        expect(Number(line.quantity), 'the browser posted ONE unit, not a blank quantity').to.eq(1)
        expect(Number(line.totalAmount), 'and charged for one').to.eq(500)
      })
    })
  })

  it('⭐ Configuration "Show the Serial / IMEI field" OFF hides the box on the till; ON brings it back', () => {
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key: SHOW, value: 'false' } })
      .its('body.success').should('eq', true)
    openSale()
    serialCell().should('not.be.visible')
    cy.get('#sellQuantity').should('be.visible')                // QTY never goes with it

    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: SHOW } })
      .its('body.success').should('eq', true)
    openSale()
    cy.get('#sellSerials').should('be.visible')
  })

  it('⭐ a shop WITHOUT serial tracking never sees the box (cross-tenant)', () => {
    /*
     * ⚠ ESTABLISHED, not inherited (GATE-RUNBOOK §5). The runbook lists owner.business@ as `retail`, but a FRESH seed
     * leaves it with no shape at all — `general`, whose preset is every capability, serial tracking included — so
     * "it does not track serials" was false here (verify sweep, 2026-10-05). Switching a capability OFF is always
     * allowed; the tenant's own override (or its absence) is put back exactly afterwards.
     */
    const KEY = 'org.cap.serialTracking'
    cy.loginAsOwner()
    configValue(KEY).then((row) => {
      bizCapWas = { value: row && row.isDefault === false ? String(row.value) : null }
      cy.setCapability('serialTracking', false)
      cy.getCapabilities().its('serialTracking').should('not.eq', true)
      openSale()
      serialCell().should('not.be.visible')
      cy.get('#sellQuantity').should('be.visible').and('not.have.attr', 'readonly')
    })
  })
})
