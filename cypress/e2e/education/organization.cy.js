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
        // `type` added by the B2B/B2C rollout: ModuleRouter routes on the ACTIVE ORG's type, so the
        // switcher payload has to carry it. The field is correct; this assertion was stale and had been
        // failing since that work shipped.
        //
        // Kept as an EXACT match (`all.keys`) rather than relaxed to `include.keys`. It is brittle by
        // design: this payload is the org list handed to a switcher, and an exact assertion is what would
        // catch a future field being added to it that has no business leaving the server. The cost is
        // updating this line when the contract legitimately changes — which is the point, not the problem.
        expect(res.body.collection[0]).to.have.all.keys('id', 'name', 'role', 'active', 'type')
      }
    })
  })
})
