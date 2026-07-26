/**
 * Party bridge (P3) — welfare Donator. Registering a Donator best-effort links it to the shared party master
 * (party-service) and stamps party_id. Two donators sharing a mobile resolve to the SAME partyId (de-dup within the
 * org). Requires party + welfare + gateway + monolith up. The bridge runs synchronously within addDonator (upsert +
 * stamp commit before it returns), so party_id is readable immediately; best-effort, so with party-service down the
 * donator still saves (null party_id) — this spec needs party up. addDonator rejects a duplicate NAME, so the two
 * donators use different names but the same mobile.
 */
describe('Party bridge (P3) — welfare Donator', () => {
  beforeEach(() => { cy.loginAsWelfare() })

  const rows = (b) => b.collection || b.data || []
  const find = (name) => cy.request('/getUserDonator').then((r) => cy.wrap(rows(r.body).find((d) => d.name === name) || null))

  it('two donators sharing a mobile map to the same partyId', () => {
    const stamp = Date.now()
    const mobile = '03' + String(stamp).slice(-9)   // valid, unique
    const a = 'PBDonA_' + stamp
    const b = 'PBDonB_' + stamp

    cy.request({ method: 'POST', url: '/addDonator', form: true, body: { name: a, mobile, amount: 100 }, failOnStatusCode: false })
      .then((r) => expect(JSON.stringify(r.body), 'donator A created').to.match(/SUCCESS/))

    find(a).then((da) => {
      expect(da, 'donator A row').to.exist
      expect(da.partyId, 'donator A got a partyId').to.be.a('number')
      const partyId = da.partyId

      cy.request({ method: 'POST', url: '/addDonator', form: true, body: { name: b, mobile, amount: 50 }, failOnStatusCode: false })
        .then((r) => expect(JSON.stringify(r.body), 'donator B created').to.match(/SUCCESS/))

      find(b).then((db) => {
        expect(db, 'donator B row').to.exist
        expect(db.partyId, 'donator B mapped to the SAME party (same mobile)').to.eq(partyId)
      })
    })
  })
})
