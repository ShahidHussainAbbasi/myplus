/**
 * Party bridge (P3) — pharmacy Prescription patient. Recording a Prescription best-effort links its patient (a
 * denormalized name/phone, not a Patient entity) to the shared party master (party-service) and stamps party_id on
 * the prescription. Two prescriptions sharing a patient phone resolve to the SAME partyId (de-dup within the org) —
 * so a pharmacy patient is the same identity as their POS customer with that phone. Requires party + pharma + catalog
 * + gateway + monolith up. The bridge runs AFTER the create tx commits (off the domain transaction), so party_id is
 * read back with a follow-up GET, not off the create response (which is serialized before the stamp).
 */
describe('Party bridge (P3) — pharmacy Prescription', () => {
  beforeEach(() => { cy.loginAsPharma() })

  const readParty = (id) => cy.request(`/getPrescription?id=${id}`).then((r) => (r.body.data || {}).partyId)

  it('two prescriptions sharing a patient phone map to the same partyId', () => {
    const stamp = Date.now()
    const phone = '03' + String(stamp).slice(-9)   // valid, unique

    cy.seedProduct({ name: 'PBRx_' + stamp, sellingPrice: 20, stock: 20 }).then(({ productId, name }) => {
      const rx = (patient) => cy.request({
        method: 'POST', url: '/addPrescription', headers: { 'Content-Type': 'application/json' },
        body: { patientName: patient, patientPhone: phone, items: [{ productId, medicineName: name, quantity: 1 }] },
        failOnStatusCode: false,
      })

      rx('PBPat_' + stamp).then((a) => {
        expect(a.body.data, JSON.stringify(a.body)).to.exist
        readParty(a.body.data.id).then((partyId) => {
          expect(partyId, 'prescription A got a partyId').to.be.a('number')

          // A second prescription for the same phone → the same party.
          rx('PBPat2_' + stamp).then((b) => {
            readParty(b.body.data.id).then((pid2) => {
              expect(pid2, 'prescription B mapped to the SAME party (same phone)').to.eq(partyId)
            })
          })
        })
      })
    })
  })
})
