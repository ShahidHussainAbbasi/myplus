/**
 * Party / contact master — P0 scaffold. The standalone party-service owns common identity + issues a stable partyId,
 * and de-dups within a tenant so the same person resolves to ONE party. Requests go to the gateway (:8765) with a
 * Bearer token (the service has no monolith proxy yet — that's P1). Requires eureka+config+gateway+auth+party up.
 */
describe('Party master (P0)', () => {
  const GW = 'http://localhost:8765'
  const PW = 'Demo@2025!'
  const login = (email) =>
    cy.request({ method: 'POST', url: `${GW}/api/auth/login`, headers: { 'Content-Type': 'application/json' }, body: { email, password: PW }, failOnStatusCode: false })
      .then((r) => { expect(r.status, JSON.stringify(r.body)).to.eq(200); return r.body.data.accessToken })
  const hdr = (t) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' })

  it('creates a party and upserts de-dupe by contact then by email', () => {
    login('demo.business@myplus.com').then((token) => {
      const stamp = Date.now()
      const contact = '0300' + stamp
      const email = `p${stamp}@t.com`

      // Create a party.
      cy.request({ method: 'POST', url: `${GW}/api/party/parties`, headers: hdr(token), body: { partyType: 'CUSTOMER', name: 'Party ' + stamp, contact, email }, failOnStatusCode: false })
        .then((c) => {
          expect(c.status, JSON.stringify(c.body)).to.eq(200)
          const id = c.body.id
          expect(id, 'partyId issued').to.be.a('number')

          // Upsert with the SAME contact → the same party (de-dup by contact), no duplicate.
          cy.request({ method: 'POST', url: `${GW}/api/party/parties/upsert`, headers: hdr(token), body: { partyType: 'CUSTOMER', name: 'Party ' + stamp + ' again', contact }, failOnStatusCode: false })
            .then((u) => expect(u.body.id, 'de-dup by contact → same partyId').to.eq(id))

          // Upsert with a DIFFERENT contact but the SAME email → de-dup by email → still the same party.
          cy.request({ method: 'POST', url: `${GW}/api/party/parties/upsert`, headers: hdr(token), body: { partyType: 'CUSTOMER', name: 'Party ' + stamp, contact: '0399' + stamp, email }, failOnStatusCode: false })
            .then((u2) => expect(u2.body.id, 'de-dup by email → same partyId').to.eq(id))

          // Resolve by id.
          cy.request({ url: `${GW}/api/party/parties/${id}`, headers: hdr(token), failOnStatusCode: false })
            .then((g) => { expect(g.status).to.eq(200); expect(String(g.body.name)).to.contain('Party ' + stamp) })
        })
    })
  })
})
