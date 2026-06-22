/**
 * Pharmacy functional smoke test (slice 33) — proves the PHARMA vertical can actually *transact*
 * through the reused trade backend, not just render. As demo.pharma it renders a registration section
 * on /pharmaDashboard, creates/reads/deletes a record via the same trade endpoints the business vertical
 * uses, and submits the dashboard form. (Display says "Distributor"/"Medicine"; the form ids + endpoints
 * are the shared trade ones — that's the whole point of the reuse.)
 *
 * Assumes a fresh demo.pharma org (seeded on auth-service start). Run headed:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/pharmacy/crud.cy.js
 */
describe('Pharmacy dashboard transacts on the reused trade backend', () => {
  beforeEach(() => {
    cy.loginAsPharma()
  })

  it('renders a registration section (Distributor/Company) on /pharmaDashboard', () => {
    cy.openSection('CompanyDiv', '/pharmaDashboard')
    cy.get('#Company').should('exist')
    cy.get('#companyName').should('be.visible')
    cy.get('#addCompany').should('be.visible')
    cy.get('#tableCompany').should('exist')
  })

  it('create -> read -> delete a record via the reused trade endpoints', () => {
    const name = `PharmaCo_${Date.now()}`
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name, email: `${Date.now()}@pharma.test` } })
      .its('status').should('eq', 200)

    cy.request('/getUserCompany').then((res) => {
      expect(res.status).to.eq(200)
      const created = res.body.data?.find((c) => c.name === name)
      expect(created, 'pharma-created company persisted in the trade backend').to.exist
      // clean up so the demo org stays tidy (and avoids the addCompany dup-check on re-runs)
      cy.request({ method: 'POST', url: '/deleteCompany', form: true, body: { checked: created.id } })
    })
  })

  it('submits the pharmacy dashboard form (UI path) successfully', () => {
    const name = `PharmaForm_${Date.now()}`
    cy.openSection('CompanyDiv', '/pharmaDashboard')
    cy.get('#companyName').type(name)
    cy.get('#companyEmail').clear().type(`${Date.now()}@pharma.test`)
    cy.intercept('POST', '/addCompany').as('addCompany')
    cy.get('#addCompany').click()
    cy.wait('@addCompany').its('response.body.status').should('be.oneOf', ['SUCCESS', 'FOUND'])

    // clean up whatever was created
    cy.request('/getUserCompany').then((res) => {
      const created = res.body.data?.find((c) => c.name === name)
      if (created) cy.request({ method: 'POST', url: '/deleteCompany', form: true, body: { checked: created.id } })
    })
  })
})
