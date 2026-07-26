/**
 * Party bridge (P3) — education Student. Registering a Student best-effort links it to the shared party master
 * (party-service) and stamps party_id. Two students sharing a mobile resolve to the SAME partyId (de-dup within the
 * org) — the same contact is one identity. Requires party + education + gateway + monolith up. The bridge runs
 * synchronously within addStudent (upsert + stamp commit before it returns), so party_id is readable immediately;
 * it's best-effort, so with party-service down the student still saves (null party_id) — this spec needs party up.
 */
describe('Party bridge (P3) — education Student', () => {
  beforeEach(() => { cy.loginAsEducation() })

  const rows = (b) => b.collection || b.data || []
  const find = (name) => cy.request('/getUserStudent').then((r) => cy.wrap(rows(r.body).find((s) => s.name === name) || null))

  it('two students sharing a mobile map to the same partyId', () => {
    const stamp = Date.now()
    const mobile = '03' + String(stamp).slice(-9)   // valid, unique
    const a = 'PBStuA_' + stamp
    const b = 'PBStuB_' + stamp

    cy.request({ method: 'POST', url: '/addStudent', form: true, body: { name: a, enrollNo: `EA${stamp}`, mobile, email: `sa${stamp}@t.com`, status: 'ACTIVE' }, failOnStatusCode: false })
      .then((r) => expect(JSON.stringify(r.body), 'student A created').to.match(/SUCCESS/))

    find(a).then((sa) => {
      expect(sa, 'student A row').to.exist
      expect(sa.partyId, 'student A got a partyId').to.be.a('number')
      const partyId = sa.partyId

      // Second student, same mobile → de-dup to the same party.
      cy.request({ method: 'POST', url: '/addStudent', form: true, body: { name: b, enrollNo: `EB${stamp}`, mobile, email: `sb${stamp}@t.com`, status: 'ACTIVE' }, failOnStatusCode: false })
        .then((r) => expect(JSON.stringify(r.body), 'student B created').to.match(/SUCCESS/))

      find(b).then((sb) => {
        expect(sb, 'student B row').to.exist
        expect(sb.partyId, 'student B mapped to the SAME party (same mobile)').to.eq(partyId)
      })
    })
  })
})
