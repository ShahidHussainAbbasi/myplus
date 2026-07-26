/**
 * Party bridge (P3) — marketplace StorefrontCustomer. An online shopper registration best-effort links to the shared
 * party master (party-service) by email — so the SAME person as a POS customer and an online shopper resolves to ONE
 * party. Verified via the contact-360 view: a POS customer + a storefront registration sharing an email show BOTH a
 * business/CUSTOMER and a marketplace/CUSTOMER role on the same party. The storefront bridge is anonymous, so it stamps
 * the org via runAs. Requires party + marketplace + business + gateway + monolith up (demo.marketplace has superSet →
 * can create the POS customer and read the owner-gated contact view). Run headed.
 */
describe('Party bridge (P3) — storefront shopper', () => {
  const parse = (b) => { try { return typeof b === 'string' ? JSON.parse(b) : b; } catch (e) { return {}; } }

  it('a storefront registration + a POS customer sharing an email map to one party (two roles)', () => {
    cy.loginAsMarketplace()
    const ts = Date.now()
    const email = `pbstore${ts}@example.com`
    const contact = '03' + String(ts).slice(-9)   // valid mobile for the POS customer
    const name = 'PBStore_' + ts

    cy.request('/getMyOrganizations').then((org) => {
      const orgId = ((org.body.collection || [])[0] || {}).id
      expect(orgId, 'store org id').to.be.a('number')

      // 1) A POS customer with this email → bridged to a party (business/CUSTOMER role).
      cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name, contact, email }, failOnStatusCode: false })
        .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      cy.request('/getUserCustomer').then((cr) => {
        const c = (cr.body.collection || cr.body.data || []).find((x) => x.name === name)
        expect(c, 'POS customer row').to.exist
        expect(c.partyId, 'POS customer bridged to a party').to.be.a('number')
        const partyId = c.partyId

        // 2) Register an online shopper with the SAME email → dedups to the same party + a marketplace/CUSTOMER role.
        cy.request({ method: 'POST', url: '/storefront/register', headers: { 'Content-Type': 'application/json' },
          body: { organizationId: orgId, email, password: 'secret123', name }, failOnStatusCode: false })
          .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

        // 3) The contact view now shows BOTH roles on the one party.
        cy.request(`/partyRoles?id=${partyId}`).then((v) => {
          const d = parse(v.body)
          expect(d.party && d.party.id, 'identity').to.eq(partyId)
          const roles = d.roles || []
          const biz = roles.find((x) => String(x.module).toLowerCase() === 'business' && x.role === 'CUSTOMER')
          const store = roles.find((x) => String(x.module).toLowerCase() === 'marketplace' && x.role === 'CUSTOMER')
          expect(biz, 'business CUSTOMER role').to.exist
          expect(store, 'marketplace CUSTOMER role (storefront bridge)').to.exist
        })
      })
    })
  })
})
