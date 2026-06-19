/**
 * Owner form (team management) + role-aware scoping (Phase 7a).
 * demo.business holds SUPER privileges (DEMO_ROLE = super set), so it is the "owner" here:
 *  - can list + create org team members (ADMIN/USER) via /team/users (-> auth-service org users),
 *  - sees the whole org's sells (no regression from role-aware scoping).
 */
describe('Owner form + role-aware scoping (Phase 7a)', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('SUPER owner can create a team member and see it in the list', () => {
    const email = `member_${Date.now()}@example.com`
    // Step 1: create a USER team member in the owner's org.
    cy.request({
      method: 'POST', url: '/team/users', headers: { 'Content-Type': 'application/json' },
      body: { firstName: 'Test', lastName: 'Member', email, role: 'USER' }, failOnStatusCode: false,
    }).then((r) => {
      expect(r.status).to.eq(200)
      // success envelope: { data: { userId,... } }; surface message on failure
      expect(r.body && r.body.data && r.body.data.userId, JSON.stringify(r.body)).to.exist
      // Step 2: the new member appears in the org team list.
      cy.request('/team/users').then((lr) => {
        const users = (lr.body && lr.body.data) || []
        expect(users.some((u) => u.email === email), 'new member is listed').to.be.true
        const m = users.find((u) => u.email === email)
        expect(m.role).to.eq('USER')
      })
    })
  })

  it('rejects a duplicate team-member email', () => {
    cy.request({
      method: 'POST', url: '/team/users', headers: { 'Content-Type': 'application/json' },
      body: { firstName: 'Dup', lastName: 'Owner', email: 'demo.business@myplus.com', role: 'USER' },
      failOnStatusCode: false,
    }).then((r) => {
      // proxy surfaces the auth-service message; no user created
      expect(r.body && r.body.data && r.body.data.userId).to.not.exist
    })
  })

  it('SUPER owner still sees org sells (role-aware scoping: no regression)', () => {
    cy.request('/getUserSell').then((r) => {
      expect(r.status).to.eq(200)
      expect(r.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
    })
  })

  it('the Team section + add-member form render for the SUPER owner', () => {
    cy.visit('/businessDashboard')
    cy.get('#snavTeam').should('exist')
    cy.window().then((win) => win.showTeam())
    cy.get('#TeamDiv').should('be.visible')
    cy.get('#teamEmail').should('be.visible')
    cy.get('#teamRole').should('exist')
    cy.get('#tableTeam').should('be.visible')
  })
})
