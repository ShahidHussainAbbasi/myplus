/**
 * PERF — the customer picker reads a lean projection, not the whole customer master.
 *
 * <h3>What changed</h3>
 * `/getUserCustomer` returns 22 fields for every customer — ~215KB across 441 rows, unpaginated, on every
 * open of the sale screen. The dropdown uses six of them. `/customerOptions` selects exactly those six as a
 * constructor projection, so Hibernate never builds a managed entity, never walks `customerHistory`, and
 * ModelMapper never runs. The smaller payload is the visible half of the saving.
 *
 * <h3>The property that actually matters</h3>
 * Not the size — the SCOPING. Both reads are role-aware: a whole-org viewer sees the org's customers,
 * everyone else only their own. If the lean read scoped even slightly differently it would show an operator
 * a customer they cannot otherwise open, or hide one they can, and **nothing on the screen would reveal
 * either**. So the central case asserts the two reads return the same customers for the same caller.
 *
 * <h3>Why `/getUserCustomer` is still here</h3>
 * Untouched on purpose: forty specs read it, and screens legitimately need the full record — partyId,
 * addresses, licence details. This is the PERF-8 shape, a lean projection ALONGSIDE the full read.
 */
describe('Customer picker reads a lean projection', () => {
  const GW = 'http://localhost:8765'

  const auth = (email) => cy.request({
    method: 'POST', url: `${GW}/api/auth/login`,
    headers: { 'Content-Type': 'application/json' },
    body: { email, password: 'Demo@2025!' }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `login ${email}`).to.eq(200)
    return { Authorization: `Bearer ${r.body.data.accessToken}` }
  })

  const ids = (body) => ((body && body.collection) || []).map((c) => c.customerId).sort((a, b) => a - b)

  // ── the property ────────────────────────────────────────────────────────────────────────────

  it('THE CASE — the lean read returns exactly the customers the full read does', () => {
    auth('owner.business@myplus.com').then((h) => {
      let full
      cy.request({ url: `${GW}/api/business/getUserCustomer`, headers: h })
        .then((r) => {
          full = ids(r.body)
          // POSITIVE CONTROL: if the full read came back empty, "they match" would be vacuously true and
          // this whole file would prove nothing.
          expect(full.length, 'the tenant has customers to compare').to.be.greaterThan(0)
        })
      cy.then(() => cy.request({ url: `${GW}/api/business/customerOptions`, headers: h }))
        .then((r) => {
          expect(ids(r.body), 'same customers, same scoping').to.deep.eq(full)
        })
    })
  })

  it('carries the six fields the dropdown binds, and nothing it does not', () => {
    /*
     * The dropdown writes customerId, name, contact, dueAmount, creditLimit and customerType onto its
     * options. A projection missing one of those renders a blank data-attribute rather than an error —
     * the credit warning at the counter would simply stop appearing, silently.
     */
    auth('owner.business@myplus.com').then((h) => {
      cy.request({ url: `${GW}/api/business/customerOptions`, headers: h }).then((r) => {
        const rows = r.body.collection || []
        expect(rows.length).to.be.greaterThan(0)
        const keys = Object.keys(rows[0])
        ;['customerId', 'name', 'contact', 'dueAmount', 'creditLimit', 'customerType']
          .forEach((k) => expect(keys, `${k} is bound by the dropdown`).to.include(k))

        // And it stays a picker: no address, no CNIC, no licence, no partyId. A projection that grows
        // "because we had it" is how a dropdown quietly becomes a data export.
        ;['address', 'cnic', 'licenseNo', 'partyId', 'customerHistory', 'email']
          .forEach((k) => expect(keys, `${k} has no business in a picker`).to.not.include(k))
      })
    })
  })

  it('is materially smaller than the read it replaced', () => {
    // The reason the slice exists. Asserted as a ratio rather than a byte count, so it does not become a
    // maintenance burden as the tenant's data changes.
    auth('owner.business@myplus.com').then((h) => {
      let fullBytes
      cy.request({ url: `${GW}/api/business/getUserCustomer`, headers: h })
        .then((r) => { fullBytes = JSON.stringify(r.body).length })
      cy.then(() => cy.request({ url: `${GW}/api/business/customerOptions`, headers: h }))
        .then((r) => {
          const leanBytes = JSON.stringify(r.body).length
          expect(leanBytes, `lean ${leanBytes} vs full ${fullBytes}`).to.be.lessThan(fullBytes / 2)
        })
    })
  })

  // ── the scoping, which is the security half ─────────────────────────────────────────────────

  it('a non-whole-org caller sees the same rows through both reads', () => {
    /*
     * The endpoint branches on seesAllOrg(): whole-org viewers get the org, everyone else gets only the
     * customers they created. Two queries mirror that, and mirroring is exactly the kind of thing that
     * drifts. demo.business@ is a plain user, so this exercises the OWN-rows branch that the owner case
     * above never touches.
     */
    auth('demo.business@myplus.com').then((h) => {
      let full
      cy.request({ url: `${GW}/api/business/getUserCustomer`, headers: h, failOnStatusCode: false })
        .then((r) => { full = ids(r.body) })
      cy.then(() => cy.request({ url: `${GW}/api/business/customerOptions`, headers: h, failOnStatusCode: false }))
        .then((r) => {
          expect(ids(r.body), 'the own-rows branch scopes identically too').to.deep.eq(full)
        })
    })
  })

  it('another tenant sees their own customers, not this one\'s', () => {
    // Both reads are tenant-scoped; a projection that dropped the org predicate would be a cross-tenant
    // leak that no screen would show.
    let mine
    auth('owner.business@myplus.com')
      .then((h) => cy.request({ url: `${GW}/api/business/customerOptions`, headers: h }))
      .then((r) => { mine = ids(r.body) })

    cy.then(() => auth('owner.marketplace@myplus.com'))
      .then((h) => cy.request({ url: `${GW}/api/business/customerOptions`, headers: h, failOnStatusCode: false }))
      .then((r) => {
        const theirs = ids(r.body)
        const shared = theirs.filter((id) => mine.includes(id))
        expect(shared, 'no customer appears in both tenants').to.have.length(0)
      })
  })
})
