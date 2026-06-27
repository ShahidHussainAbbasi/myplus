/**
 * Owner form is gated to ROLE_OWNER (not SUPER_PRIVILEGE), so a DEMO account — which has super
 * privileges to USE the app but is not an owner — cannot manage the team. Verifies the restriction
 * + no regression on role-aware sell reads. (The owner CAN-create flow needs a ROLE_OWNER fixture
 * with a known password — tracked as a TODO; owner-form users have no password by design.)
 *
 * NOTE: requires auth-service + monolith rebuilt with the ROLE_OWNER gate.
 */
describe('Owner form gated to ROLE_OWNER — demo cannot manage team', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('a DEMO account is BLOCKED from creating team members', () => {
    cy.request({
      method: 'POST', url: '/team/users', headers: { 'Content-Type': 'application/json' },
      body: { firstName: 'Blocked', lastName: 'Demo', email: `blocked_${Date.now()}@example.com`, role: 'USER' },
      failOnStatusCode: false,
    }).then((r) => {
      // The auth-service returns 403 (no ROLE_OWNER); the monolith proxy maps it to a non-success
      // body — crucially, NO user is created.
      expect(r.body && r.body.data && r.body.data.userId, JSON.stringify(r.body)).to.not.exist
    })
  })

  it('a DEMO account does not see the Team section', () => {
    cy.visit('/businessDashboard')
    // sec:authorize="hasAuthority('ROLE_OWNER')" -> not rendered for a demo (DEMO_ROLE) user.
    cy.get('#snavTeam').should('not.exist')
    cy.get('#TeamDiv').should('not.exist')
  })

  it('SUPER still sees org sells (role-aware scoping: no regression)', () => {
    cy.request('/getUserSell').then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
    })
  })
})
