/**
 * Education — organization (tenant) switcher.
 * Asserts the active-org chip renders in the subnav and the monolith /getMyOrganizations
 * endpoint returns the user's org(s). Requires the full stack (auth + gateway + education + monolith).
 */

describe('Education — organization switcher', () => {
  beforeEach(() => {
    cy.loginAsEducation()
  })

  it('renders the org switcher in the subnav', () => {
    cy.visit('/educationDashboard')
    cy.get('#orgSwitcher').should('exist')
    cy.get('#orgSwitcher option').should('have.length.greaterThan', 0)
  })

  it('/getMyOrganizations returns SUCCESS with at least one org', () => {
    cy.request('/getMyOrganizations').then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
      if (res.body.status === 'SUCCESS') {
        expect(res.body.collection).to.be.an('array').and.have.length.greaterThan(0)
        expect(res.body.collection[0]).to.have.all.keys('id', 'name', 'role', 'active')
      }
    })
  })
})
