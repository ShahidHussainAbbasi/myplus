/**
 * Team management: what an ADMIN may do, and reassigning an existing member.
 *
 * Two real bugs this locks down:
 *
 * 1. ADMIN could CREATE a member but could not LIST the team — POST /api/auth/org/users returned 200 while
 *    GET returned 403, and the Manage Users screen itself was gated ROLE_OWNER, so an admin could not even
 *    open it. They were managing people blind.
 *
 * 2. Assignment was a ONE-WAY DOOR — the grant endpoint only ever added, so nobody could move a member from
 *    one store to another, or take access away. `replace:true` makes the request the member's complete set.
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const rows = (body) => {
  for (const key of ['collection', 'object', 'data']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return []
}

const member = (email) =>
  cy.request('/team/users').then((r) => cy.wrap(rows(r.body).find((u) => u.email === email) || null))

describe('Team: admin rights and reassignment', () => {
  const F = {}

  before(() => {
    // A cached session can be holding STALE LOCATION CLAIMS.
    //
    // `/getMyStores` answers from the JWT's `accessibleLocationIds`, which are minted at login. With
    // `cy.session(cacheAcrossSpecs: true)` a session from earlier in the run is replayed as-is, so if the
    // fixture stores were recreated since (they are keyed by NAME, and their IDs change whenever the
    // fixture is rebuilt — 3/4 became 5/6 during this repair pass) the token still names stores that no
    // longer exist. The endpoint then honestly reports ZERO workable stores and this hook fails with
    // "expected 0 to be above 1", pointing at the fixture when the fixture is fine — a fresh login returns
    // both stores.
    //
    // Dropping the cache first is what makes the assertion below mean what it says.
    cy.then(() => Cypress.session.clearAllSavedSessions())
    cy.loginAsOwner()
    cy.request('/getMyStores').then((r) => {
      const stores = rows(r.body)
      expect(stores.length, 'the multi-location fixture stores exist (run multi-location.cy.js first)')
        .to.be.greaterThan(1)
      F.storeA = stores.find((s) => s.name === 'CY Store A').id
      F.storeB = stores.find((s) => s.name === 'CY Store B').id
    })
  })

  /**
   * Put cashier.a back on Store A, whatever happened above.
   *
   * The last case deliberately revokes ALL of this member's stores, and grants are SERVER state that
   * `testIsolation` never touches — so the account is left bare for `void-invoice-privilege` and the two
   * pharmacy specs that run after this one, and for the next run of `multi-location`, whose whole premise
   * is that cashier.a holds exactly Store A.
   *
   * It is not currently destructive (the platform reads "no grants" as UNCONSTRAINED, the
   * pre-multi-location behaviour), which is precisely why it would go unnoticed until a change made
   * absent grants mean something narrower. Restoring the fixture costs one request.
   *
   * As the OWNER: the cases above act as an admin on purpose, and an admin may only revoke or grant
   * stores they themselves hold.
   */
  after(() => {
    cy.loginAsOwner()
    cy.request('/team/users').then((r) => {
      const m = rows(r.body).find((u) => u.email === 'cashier.a@myplus.com')
      if (!m || !F.storeA) return
      cy.request({
        method: 'POST', url: '/assignStores', headers: { 'Content-Type': 'application/json' },
        body: { userId: m.userId, storeIds: [F.storeA], roleAtLocation: 'USER', replace: true },
        failOnStatusCode: false,
      }).then((g) => expect(g.body && g.body.success, `restore cashier.a to Store A: ${JSON.stringify(g.body)}`).to.eq(true))
    })
  })

  it('an admin can LIST the team (this used to 403)', () => {
    cy.loginAsStoreAdmin()
    cy.request({ url: '/team/users', failOnStatusCode: false }).then((r) => {
      expect(r.status, 'the endpoint answers an admin').to.eq(200)
      expect(rows(r.body).length, 'and returns their org\'s members').to.be.greaterThan(0)
    })
  })

  it('reassigning a member REPLACES their locations — an omitted store is revoked', () => {
    cy.loginAsOwner()

    // cashier.a starts on Store A (from the multi-location fixture). Move them to Store B only.
    member('cashier.a@myplus.com').then((m) => {
      expect(m, 'cashier.a is on the team').to.exist
      expect(m.locationIds, 'the team list now reports where each member works').to.be.an('array')

      cy.request({
        method: 'POST', url: '/assignStores', headers: { 'Content-Type': 'application/json' },
        body: { userId: m.userId, storeIds: [F.storeB], replace: true }, failOnStatusCode: false,
      }).then((r) => expect(r.body && r.body.success, JSON.stringify(r.body)).to.eq(true))

      member('cashier.a@myplus.com').then((after) => {
        const ids = (after.locationIds || []).map(Number)
        expect(ids, 'moved to Store B').to.include(Number(F.storeB))
        expect(ids, 'and Store A was actually REVOKED, not merely added to').to.not.include(Number(F.storeA))
      })
    })
  })

  it('restores cashier.a to Store A (so the multi-location fixture still holds)', () => {
    cy.loginAsOwner()
    member('cashier.a@myplus.com').then((m) => {
      cy.request({
        method: 'POST', url: '/assignStores', headers: { 'Content-Type': 'application/json' },
        body: { userId: m.userId, storeIds: [F.storeA], replace: true }, failOnStatusCode: false,
      }).then((r) => expect(r.body && r.body.success).to.eq(true))

      member('cashier.a@myplus.com').then((after) => {
        const ids = (after.locationIds || []).map(Number)
        expect(ids, 'back on Store A').to.include(Number(F.storeA))
        expect(ids, 'and off Store B').to.not.include(Number(F.storeB))
      })
    })
  })

  it('an admin cannot revoke a store they do not manage', () => {
    // admin.store holds Store B only. They must not be able to strip cashier.a of Store A by omitting it.
    cy.loginAsStoreAdmin()
    member('cashier.a@myplus.com').then((m) => {
      cy.request({
        method: 'POST', url: '/assignStores', headers: { 'Content-Type': 'application/json' },
        body: { userId: m.userId, storeIds: [], replace: true }, failOnStatusCode: false,
      })
      cy.loginAsOwner()
      member('cashier.a@myplus.com').then((after) => {
        expect((after.locationIds || []).map(Number), 'Store A survived an admin who does not hold it')
          .to.include(Number(F.storeA))
      })
    })
  })
})
