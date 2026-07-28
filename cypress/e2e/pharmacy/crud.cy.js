/**
 * Pharmacy functional smoke test (slice 33) — proves the PHARMA vertical can actually *transact*
 * through the reused trade backend, not just render. As demo.pharma it renders a registration section
 * on the shared commerce dashboard, creates/reads/deletes a record via the same trade endpoints the business vertical
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

  it('renders a registration section (Distributor/Company) on the shared commerce dashboard', () => {
    cy.openSection('CompanyDiv', '/businessDashboard')
    cy.get('#tableCompany').should('exist')     // the section itself: the list is what renders in place
    // The form moved into the shared CRUD modal (#CompanyModal), so its fields are display:none until the
    // modal is opened — same flow the "submits the form (UI path)" test below already uses.
    cy.get('#newCompany').click()
    cy.get('#CompanyModal').should('have.class', 'open')
    cy.get('#Company').should('exist')
    cy.get('#companyName').should('be.visible')
    cy.get('#addCompany').should('be.visible')
  })

  it('create -> read -> delete a record via the reused trade endpoints', () => {
    const name = `PharmaCo_${Date.now()}`
    // Assert the CREATE succeeded, not just that it returned 200: the proxy answers 200 with an error envelope
    // when the write is refused (demo entry cap, duplicate name, upstream down). Without this the run failed
    // later at "expected undefined to exist", which points at the read instead of the write that actually broke.
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name, email: `${Date.now()}@pharma.test` } })
      .then((r) => {
        expect(r.status).to.eq(200)
        expect(String(r.body.status || ''), `addCompany: ${JSON.stringify(r.body)}`).to.not.match(/ERROR|FAILED/i)
      })

    cy.request('/getUserCompany').then((res) => {
      expect(res.status).to.eq(200)
      // GenericResponse carries lists in `collection`; `data` is the newer envelope. Accept either.
      const list = res.body.collection || res.body.data || []
      const created = list.find((c) => c.name === name)
      expect(created, `pharma-created company persisted in the trade backend: ${JSON.stringify(res.body).slice(0, 200)}`).to.exist
      // clean up so the demo org stays tidy (and avoids the addCompany dup-check on re-runs)
      cy.request({ method: 'POST', url: '/deleteCompany', form: true, body: { checked: created.id } })
    })
  })

  it('submits the pharmacy dashboard form (UI path) successfully', () => {
    const name = `PharmaForm_${Date.now()}`
    cy.openSection('CompanyDiv', '/businessDashboard')
    cy.get('#newCompany').click()                       // form moved into a modal
    cy.get('#CompanyModal').should('have.class', 'open')
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
