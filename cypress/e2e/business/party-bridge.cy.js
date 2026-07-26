/**
 * Party bridge (P1). Registering a business Customer or Vender best-effort links it to the shared party master
 * (party-service) and stamps party_id. A customer and a vendor that share a contact resolve to the SAME partyId —
 * proving one identity across modules (the point of the master). Requires party + business + gateway + monolith up.
 * The bridge runs synchronously within the register request (upsert + stamp commit before it returns), so the
 * party_id is readable immediately after — but it's best-effort, so if party-service is down the register still
 * succeeds with a null party_id (this spec needs party-service up).
 */
describe('Party bridge (P1)', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  const list = (b) => b.collection || b.data || []

  it('a customer and a vendor sharing a contact map to the same partyId', () => {
    const stamp = Date.now()
    // Shared value used as BOTH the customer contact and the vendor mobile (that's what proves cross-type de-dup).
    // Must be a valid mobile (03 + 9 digits = 11) for the vendor @ValidMobileNumber, and unique per run.
    const contact = '03' + String(stamp).slice(-9)
    const email = `pb${stamp}@t.com`
    const cname = 'PBCust_' + stamp

    // Register a customer → it gets a party_id.
    cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name: cname, contact, email }, failOnStatusCode: false })
      .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

    cy.request('/getUserCustomer').then((cr) => {
      const c = list(cr.body).find((x) => x.name === cname)
      expect(c, 'customer row').to.exist
      expect(c.partyId, 'customer got a partyId').to.be.a('number')
      const partyId = c.partyId

      // Register a vendor with the SAME contact (mobile) + email under the same org → de-dup to the same party.
      cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'PBCo_' + stamp, email: `co${stamp}@t.com` } })
      cy.request('/getUserCompany').then((co) => {
        const companyId = list(co.body).find((x) => x.name === 'PBCo_' + stamp).id
        cy.request({ method: 'POST', url: '/addVender', form: true, body: { name: 'PBVen_' + stamp, companyId, mobile: contact, email }, failOnStatusCode: false })
          .then((v) => expect(v.body.status, JSON.stringify(v.body)).to.eq('SUCCESS'))

        cy.request('/getUserVender').then((vr) => {
          const ven = list(vr.body).find((x) => x.name === 'PBVen_' + stamp)
          expect(ven, 'vendor row').to.exist
          expect(ven.partyId, 'vendor mapped to the SAME party as the customer').to.eq(partyId)
        })
      })
    })
  })
})
