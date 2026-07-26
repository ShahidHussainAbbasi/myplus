/**
 * Cross-module contact view (P4). The bridges made one person ONE partyId; this proves the payoff — party-service
 * keeps a denormalized ROLE INDEX (party_role_link), written by the same AFTER_COMMIT bridge (piggybacked on the
 * existing upsert, no extra call), and answers "who is this person to us across everything we run?" in ONE query.
 *
 * Records are created through the monolith proxies (a real write path, so the bridge runs); the role index is read
 * from the gateway with a Bearer token (party-service has no monolith proxy until P4b).
 *
 * Cross-module coverage relies on ONE org exercising TWO modules: demo.business (DEMO_ROLE = full privileges) creates
 * both the POS customer and the pharmacy prescription, so both land in the same tenant and dedupe onto one party.
 *
 * Requires party + business + pharma + catalog + gateway + monolith up.
 */
describe('Party contact view (P4)', () => {
  const GW = 'http://localhost:8765'
  const PW = 'Demo@2025!'

  const login = (email) =>
    cy.request({ method: 'POST', url: `${GW}/api/auth/login`, headers: { 'Content-Type': 'application/json' }, body: { email, password: PW }, failOnStatusCode: false })
      .then((r) => { expect(r.status, JSON.stringify(r.body)).to.eq(200); return r.body.data.accessToken })

  const hdr = (t) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' })
  const list = (b) => b.collection || b.data || []
  const roles = (t, partyId) =>
    cy.request({ url: `${GW}/api/party/parties/${partyId}/roles`, headers: hdr(t), failOnStatusCode: false })
  const rolesOf = (body, module, role) => (body.roles || []).filter((r) => r.module === module && r.role === role)

  beforeEach(() => { cy.loginAsBusiness() })   // testIsolation clears the session between tests

  it('shows a POS customer and a pharmacy patient as roles of ONE party', () => {
    const stamp = Date.now()
    const phone = '03' + String(stamp).slice(-9)     // valid mobile, unique per run — the de-dup key
    const cname = 'CV_Cust_' + stamp

    cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name: cname, contact: phone, email: `cv${stamp}@t.com` }, failOnStatusCode: false })
      .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

    cy.request('/getUserCustomer').then((cr) => {
      const c = list(cr.body).find((x) => x.name === cname)
      expect(c, 'customer row').to.exist
      expect(c.partyId, 'customer got a partyId').to.be.a('number')

      // Same tenant, same phone, different module → the prescription dedupes onto the SAME party.
      cy.seedProduct({ name: 'CVRx_' + stamp, sellingPrice: 20, stock: 20 }).then(({ productId, name }) => {
        cy.request({
          method: 'POST', url: '/addPrescription', headers: { 'Content-Type': 'application/json' },
          body: { patientName: 'CV_Pat_' + stamp, patientPhone: phone, items: [{ productId, medicineName: name, quantity: 1 }] },
          failOnStatusCode: false,
        }).then((rx) => {
          expect(rx.body.data, JSON.stringify(rx.body)).to.exist
          const rxId = rx.body.data.id

          cy.request(`/getPrescription?id=${rxId}`).then((g) => {
            expect((g.body.data || {}).partyId, 'prescription mapped to the SAME party as the customer').to.eq(c.partyId)
          })

          login('demo.business@myplus.com').then((token) => {
            roles(token, c.partyId).then((res) => {
              expect(res.status, JSON.stringify(res.body)).to.eq(200)
              expect(res.body.party.contact, 'the shared identity').to.eq(phone)

              const asCustomer = rolesOf(res.body, 'business', 'CUSTOMER')
              const asPatient = rolesOf(res.body, 'pharma', 'PATIENT')
              expect(asCustomer.length, 'a business/CUSTOMER role').to.eq(1)
              expect(asCustomer[0].localId, 'points at the local customer row').to.eq(c.customerId || c.id)
              expect(asPatient.length, 'a pharma/PATIENT role — the cross-module payoff').to.eq(1)
              expect(asPatient[0].localId, 'points at the local prescription').to.eq(rxId)
            })
          })
        })
      })
    })
  })

  it('records the same link twice without duplicating it (idempotent index)', () => {
    // The bridge is retried by design (edit paths re-stamp, breaker cooldowns re-fire, the backfill is re-runnable),
    // so a repeat write must be a no-op — that is the uq_role_link ON DUPLICATE KEY path.
    const stamp = Date.now()
    login('demo.business@myplus.com').then((token) => {
      cy.request({ method: 'POST', url: `${GW}/api/party/parties`, headers: hdr(token), body: { partyType: 'CUSTOMER', name: 'CV_Idem_' + stamp, contact: '0311' + String(stamp).slice(-7) } })
        .then((p) => {
          const partyId = p.body.id
          const link = () => cy.request({ method: 'POST', url: `${GW}/api/party/parties/${partyId}/roles`, headers: hdr(token), body: { module: 'business', role: 'CUSTOMER', localId: 4242, label: 'CV_Idem' }, failOnStatusCode: false })

          link().then((a) => expect(a.status, JSON.stringify(a.body)).to.eq(200))
          link().then((b) => expect(b.status, 'a repeat link is accepted, not a 500').to.eq(200))

          roles(token, partyId).then((res) => {
            expect(rolesOf(res.body, 'business', 'CUSTOMER').length, 'still exactly ONE row after two writes').to.eq(1)
          })
        })
    })
  })

  it('an identity-only upsert records no role (P0/P1 back-compat)', () => {
    const stamp = Date.now()
    login('demo.business@myplus.com').then((token) => {
      cy.request({ method: 'POST', url: `${GW}/api/party/parties/upsert`, headers: hdr(token), body: { partyType: 'CUSTOMER', name: 'CV_Bare_' + stamp, contact: '0322' + String(stamp).slice(-7) }, failOnStatusCode: false })
        .then((u) => {
          expect(u.status, JSON.stringify(u.body)).to.eq(200)
          roles(token, u.body.id).then((res) => {
            expect(res.status).to.eq(200)
            expect(res.body.roles, 'no role field on the upsert → no link').to.have.length(0)
          })
        })
    })
  })

  it('returns 404 for a party in another tenant (no cross-tenant existence probe)', () => {
    const stamp = Date.now()
    login('demo.education@myplus.com').then((eduToken) => {
      cy.request({ method: 'POST', url: `${GW}/api/party/parties`, headers: hdr(eduToken), body: { partyType: 'STUDENT', name: 'CV_Foreign_' + stamp, contact: '0333' + String(stamp).slice(-7) } })
        .then((p) => {
          const foreignId = p.body.id
          login('demo.business@myplus.com').then((bizToken) => {
            roles(bizToken, foreignId).then((res) => {
              expect(res.status, 'a foreign party is indistinguishable from a missing one').to.eq(404)
            })
          })
        })
    })
  })

  it('refuses the contact view to a non-admin (the privacy gate)', () => {
    // The EXISTENCE of a pharma/PATIENT role is sensitive — a cashier must not learn a customer is a patient.
    login('cashier.a@myplus.com').then((cashier) => {
      roles(cashier, 1).then((res) => {
        expect(res.status, `cashier reached the contact view: ${JSON.stringify(res.body)}`).to.eq(403)
      })
    })
  })

  it('backfills links for records bridged before P4, and is safe to re-run', () => {
    // Rows bridged earlier carry a party_id, so the skip-guard means they never bridge again — without a backfill
    // they would never appear in the view.
    const stamp = Date.now()
    const phone = '03' + String(stamp).slice(-9)
    const cname = 'CV_Back_' + stamp

    cy.request({ method: 'POST', url: '/addCustomer', form: true, body: { name: cname, contact: phone, email: `cvb${stamp}@t.com` }, failOnStatusCode: false })
      .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

    cy.request('/getUserCustomer').then((cr) => {
      const c = list(cr.body).find((x) => x.name === cname)
      expect(c.partyId, 'customer bridged').to.be.a('number')

      const localId = c.customerId || c.id
      login('demo.business@myplus.com').then((token) => {
        // Start the cursor just before our row so the batch is deterministic regardless of how many customers the
        // demo org already has (batches are capped, so a cursor at 0 might not reach a high id).
        const backfill = () => cy.request({
          method: 'POST', headers: hdr(token), failOnStatusCode: false,
          url: `${GW}/api/business/party-links/backfill?limit=200&afterCustomerId=${localId - 1}`,
        })

        backfill().then((a) => {
          expect(a.status, JSON.stringify(a.body)).to.eq(200)
          expect(a.body.linked, 'linked at least the row we just created').to.be.greaterThan(0)
          backfill().then((b) => {
            expect(b.status, 'a second run is safe').to.eq(200)
            roles(token, c.partyId).then((res) => {
              expect(rolesOf(res.body, 'business', 'CUSTOMER').length, 'no duplicate after two backfills').to.eq(1)
            })
          })
        })
      })
    })
  })
})
