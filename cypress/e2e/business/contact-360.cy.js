/**
 * Contact-360 (party P4 UI). The owner opens a customer's cross-module contact view (/partyRoles → party-service
 * GET /parties/{id}/roles): one shared identity + every module role it plays. Owner/admin-gated on both sides — a
 * cashier is denied. Requires party + business + gateway + monolith up, with the P4 role-link recording live (the
 * bridge records a business/CUSTOMER role on customer create). Run headed.
 */
describe('Contact-360 (party contact view)', () => {
  const parse = (b) => { try { return typeof b === 'string' ? JSON.parse(b) : b; } catch (e) { return {}; } }

  it('owner sees a customer identity + business role; a cashier is denied', () => {
    cy.loginAsOwner()
    const stamp = Date.now()
    const name = 'C360_' + stamp
    const contact = '03' + String(stamp).slice(-9)

    cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name, contact }, failOnStatusCode: false })
      .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

    cy.request('/getUserCustomer').then((cr) => {
      const c = (cr.body.collection || cr.body.data || []).find((x) => x.name === name)
      expect(c, 'customer row').to.exist
      expect(c.partyId, 'customer bridged to a party').to.be.a('number')
      const pid = c.partyId

      // Owner: the contact view returns the identity + a business/CUSTOMER role.
      cy.request(`/partyRoles?id=${pid}`).then((v) => {
        const d = parse(v.body)
        expect(d.party && d.party.id, 'identity in the view').to.eq(pid)
        expect(d.roles, 'roles array').to.be.an('array')
        const biz = (d.roles || []).find((x) => String(x.module).toLowerCase() === 'business' && x.role === 'CUSTOMER')
        expect(biz, 'business CUSTOMER role recorded (P4)').to.exist
      })

      // Cashier (ROLE_BUSINESS_USER — no owner/admin) is denied the contact view.
      cy.loginAsCashierA()
      cy.request({ url: `/partyRoles?id=${pid}`, failOnStatusCode: false }).then((denied) => {
        const body = parse(denied.body)
        expect(denied.status === 403 || !(body && body.party), 'cashier denied the contact view ' + denied.status).to.eq(true)
      })
    })
  })
})
