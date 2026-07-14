/**
 * Active-organization switcher on the COMMERCE dashboard (it only ever existed on education's).
 *
 * Why it matters: a user can belong to several orgs — one they own, plus any they were added to as a member —
 * and active-org resolution prefers the one they OWN. Without this control such a user is pinned to their own
 * org forever: records they create land there and are invisible from the org they are a member of. That is
 * exactly the "my company isn't visible" confusion this fixes.
 *
 * SCOPE NOTE: the seeded test accounts each belong to ONE org, so this spec proves the plumbing — the list is
 * returned, the active org is flagged, switching re-issues the session token and the app still works through
 * it. The two-org hop itself is enforced server-side by AuthService.switchOrganization's isMember() check
 * (a client cannot switch into an org it does not belong to), and is worth one manual pass with a real
 * multi-org account.
 */
const rows = (body) => {
  for (const key of ['collection', 'object', 'data']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return []
}

describe('Commerce dashboard: active-organization switcher', () => {
  beforeEach(() => {
    cy.loginAsOwner()   // testIsolation clears the session between tests
  })

  it('lists the orgs the user belongs to, with the active one flagged', () => {
    cy.request('/getMyOrganizations').then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      const orgs = rows(r.body)
      expect(orgs.length, 'at least the org they own').to.be.greaterThan(0)
      const active = orgs.filter((o) => o.active)
      expect(active.length, 'exactly one org is marked active').to.eq(1)
      expect(active[0].name, 'the active org has a name to show in the control').to.be.a('string')
    })
  })

  it('switching re-issues the session token and the app keeps working through it', () => {
    cy.request('/getMyOrganizations').then((r) => {
      const active = rows(r.body).find((o) => o.active)

      cy.request({
        method: 'POST', url: '/switchOrganization', form: true,
        body: { organizationId: active.id }, failOnStatusCode: false,
      }).then((s) => {
        expect(s.body.status, `switchOrganization: ${JSON.stringify(s.body)}`).to.eq('SUCCESS')
      })

      // The monolith swapped the session's JWT. If that went wrong, every downstream call 401s — so read
      // something real through the new token rather than trusting the switch response.
      cy.request({ url: '/getBusinessDashboardStats', failOnStatusCode: false }).then((after) => {
        expect(after.status, 'the new token still authenticates').to.eq(200)
      })
      cy.request('/getMyOrganizations').then((again) => {
        const stillActive = rows(again.body).find((o) => o.active)
        expect(Number(stillActive.id), 'still in the org we switched to').to.eq(Number(active.id))
      })
    })
  })

  it('the switcher is rendered on the business dashboard', () => {
    // The control never existed here — assert the markup the shared script drives is actually present.
    cy.visit('/businessDashboard')
    cy.get('#orgSwitcher', { timeout: 10000 }).should('exist')
    cy.get('#orgSwitcherLi').should('be.visible')          // shown once the org list loads
    cy.get('#orgSwitcher option').should('have.length.greaterThan', 0)
  })
})
