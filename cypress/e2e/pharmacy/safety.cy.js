/**
 * Pharmacy P7 (slice 44) — dispense safety: clinical flags (controlled/rx) + drug-interaction check. As demo.pharma,
 * flag two items + add an interaction, then /checkSafety reports them. Run headed.
 */
describe('Pharmacy — dispense safety', () => {
  beforeEach(() => { cy.loginAsPharma() })

  it('checkSafety reports controlled items + interactions for the dispensed set', () => {
    // B1: clinical flags now live on the CATALOG product, so they must be set against a product that exists —
    // flagging an invented id is refused (a flag no product carries could never be enforced at the till). This
    // test used to invent ids; it now seeds two real medicines, like alerts.cy.js and rx-enforcement.cy.js.
    const stamp = Date.now()
    cy.seedProduct({ name: 'CtrlMed_' + stamp, sku: 'SF' + stamp, unit: 'tablet', stock: 10 }).then(({ productId: a }) => {
      cy.seedProduct({ name: 'OtherMed_' + stamp, sku: 'SO' + stamp, unit: 'tablet', stock: 10 }).then(({ productId: b }) => {

        cy.request({ method: 'POST', url: '/saveClinical', body: { productId: a, medicineName: 'CtrlMed', rxRequired: true, controlledSubstance: true }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
          .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
        cy.request({ method: 'POST', url: '/saveClinical', body: { productId: b, medicineName: 'OtherMed', rxRequired: false, controlledSubstance: false }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
        cy.request({ method: 'POST', url: '/addInteraction', body: { productId1: a, productId2: b, severity: 'SEVERE', description: 'A+B dangerous' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
          .then((r) => expect(r.body.success).to.eq(true))

        cy.request({ method: 'POST', url: '/checkSafety', body: { productIds: [a, b] }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false }).then((r) => {
          expect(r.status).to.eq(200)
          expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
          expect(r.body.data.controlledItems).to.include(a)
          expect(r.body.data.rxRequiredItems).to.include(a)
          expect(r.body.data.interactions).to.have.length(1)
          expect(r.body.data.interactions[0].severity).to.eq('SEVERE')
        })
      })
    })
  })

  it('a clinical flag cannot be set on a product that does not exist', () => {
    // The flag drives whether the tills refuse a sale, so it has to attach to a real product in the master.
    cy.request({ method: 'POST', url: '/saveClinical', body: { productId: 999999999, medicineName: 'Ghost', rxRequired: true }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(false)
        expect(String(r.body.message || ''), 'the reason survives the proxy').to.not.be.empty
      })
  })

  it('interaction only fires when both items are in the set', () => {
    const base = (Date.now() % 1000000000) + 500
    const a = base, b = base + 1
    cy.request({ method: 'POST', url: '/addInteraction', body: { productId1: a, productId2: b, severity: 'MODERATE' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })

    cy.request({ method: 'POST', url: '/checkSafety', body: { productIds: [a] }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false }).then((r) => {
      expect(r.body.data.interactions).to.have.length(0)
    })
    cy.request({ method: 'POST', url: '/checkSafety', body: { productIds: [a, b] }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false }).then((r) => {
      expect(r.body.data.interactions).to.have.length(1)
    })
  })

  it('Clinical & Safety panel renders for PHARMA', () => {
    cy.visit('/businessDashboard')
    cy.window().its('MODULE').should('eq', 'PHARMA')
    cy.window().should('have.property', 'showClinical')
    cy.window().then((w) => w.showClinical())
    cy.get('#ClinicalDiv').should('be.visible')
    cy.get('#clItem').should('exist')
  })
})
