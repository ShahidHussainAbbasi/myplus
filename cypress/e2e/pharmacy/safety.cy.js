/**
 * Pharmacy P7 (slice 44) — dispense safety: clinical flags (controlled/rx) + drug-interaction check. As demo.pharma,
 * flag two items + add an interaction, then /checkSafety reports them. Run headed.
 */
describe('Pharmacy — dispense safety', () => {
  beforeEach(() => { cy.loginAsPharma() })

  it('checkSafety reports controlled items + interactions for the dispensed set', () => {
    // unique itemIds per run so re-runs don't accumulate interaction rows
    const base = Date.now() % 1000000000
    const a = base, b = base + 1

    cy.request({ method: 'POST', url: '/saveClinical', body: { itemId: a, medicineName: 'CtrlMed', rxRequired: true, controlledSubstance: true }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
    cy.request({ method: 'POST', url: '/saveClinical', body: { itemId: b, medicineName: 'OtherMed', rxRequired: false, controlledSubstance: false }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
    cy.request({ method: 'POST', url: '/addInteraction', body: { itemId1: a, itemId2: b, severity: 'SEVERE', description: 'A+B dangerous' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })
      .then((r) => expect(r.body.success).to.eq(true))

    cy.request({ method: 'POST', url: '/checkSafety', body: { itemIds: [a, b] }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false }).then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.controlledItems).to.include(a)
      expect(r.body.data.rxRequiredItems).to.include(a)
      expect(r.body.data.interactions).to.have.length(1)
      expect(r.body.data.interactions[0].severity).to.eq('SEVERE')
    })
  })

  it('interaction only fires when both items are in the set', () => {
    const base = (Date.now() % 1000000000) + 500
    const a = base, b = base + 1
    cy.request({ method: 'POST', url: '/addInteraction', body: { itemId1: a, itemId2: b, severity: 'MODERATE' }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false })

    cy.request({ method: 'POST', url: '/checkSafety', body: { itemIds: [a] }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false }).then((r) => {
      expect(r.body.data.interactions).to.have.length(0)
    })
    cy.request({ method: 'POST', url: '/checkSafety', body: { itemIds: [a, b] }, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false }).then((r) => {
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
