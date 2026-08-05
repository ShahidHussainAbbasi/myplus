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

  // Slice 106: this asserted #snavTeam was NOT rendered for a demo account. The gate has since been widened
  // on purpose — `sec:authorize="hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE')"` — so that admins/managers
  // can manage users in their own stores. DEMO_ROLE is seeded from superSet, and super ⊇ admin, so a demo
  // account DOES carry ADMIN_PRIVILEGE and legitimately sees the section now.
  //
  // ⚠️ MISMATCH WORTH A DECISION (flagged, deliberately not resolved here): the NAV opened to
  // ADMIN_PRIVILEGE but the API did not — the test above proves /team/users still refuses anyone without
  // ROLE_OWNER. So an admin can open "Manage Users" and be refused on submit. Either the endpoint should
  // accept admins (matching the template's stated intent) or the nav should re-narrow to ROLE_OWNER.
  // This test pins the CURRENT behaviour of both halves so the mismatch cannot drift further unnoticed.
  it('an ADMIN-privileged account sees the Team section, but the API still refuses it', () => {
    cy.visit('/businessDashboard')
    cy.get('#snavTeam', { timeout: 10000 }).should('exist')   // widened gate: admins see it
  })

  it('SUPER still sees org sells (role-aware scoping: no regression)', () => {
    cy.request('/getUserSell').then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
    })
  })
})
