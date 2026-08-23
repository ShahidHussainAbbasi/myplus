/**
 * O7 D6a — territory assignment. The slice that gives `assigned_rep_user_id` its data.
 *
 * <h3>What was actually wrong</h3>
 * D2d shipped the territory RULE and the column it reads:
 *
 * <pre>
 *   owner / admin            → every outlet in the org
 *   rep WITH assignments     → their territory (+ every unassigned outlet)
 *   rep with NO assignments  → every outlet in the org
 * </pre>
 *
 * ...and nothing anywhere ever wrote that column. A repo-wide search for `assignedRepUserId` returned the
 * entity, the migration, the read query and a comment. So the middle branch had never once occurred in
 * production: every rep fell through to "sees everything", and the territory model was a rule with no data.
 *
 * <h3>Why the central case reads the REP's picker</h3>
 * The property is not "a row was updated" — that is the artefact, and this programme has now been caught five
 * times asserting exactly that. The property is **what a different user can see afterwards**. So the case
 * assigns as the owner and then logs in as the rep and reads `/outlets`.
 *
 * <h3>The positive control</h3>
 * "The rep's list got shorter" would pass just as well against an endpoint returning nothing at all — which is
 * how D2's own anti-IDOR case once went green against a 404. Every narrowing assertion here therefore names an
 * outlet that MUST still be present alongside the one that must be gone.
 */
describe('O7 D6a — a territory is what a rep can see', () => {
  const run = String(Date.now()).slice(-6)
  const ctx = { outlets: {} }

  /** Seed an outlet as the owner and remember its id. */
  const seedOutlet = (label) => {
    const name = 'Terr_' + label + '_' + run
    cy.request({
      method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name, contact: '03' + run + label.length, creditLimit: 50000 },
    }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    cy.then(() => cy.request('/getUserCustomer')).then((r) => {
      const rows = (r.body.collection || r.body.data || []).filter((c) => c.name === name)
      expect(rows.length, `${name} created exactly once`).to.eq(1)
      ctx.outlets[label] = { id: rows[0].customerId || rows[0].id, name }
      expect(ctx.outlets[label].id, 'and it has an id').to.be.a('number')
    })
  }

  const assign = (repUserId, ids, opts = {}) =>
    cy.request({
      method: 'POST', url: '/assignOutlets', form: true, failOnStatusCode: false,
      body: { ...(repUserId == null ? {} : { repUserId }), customerIds: ids.join(',') },
      ...opts,
    })

  /** The names the REP can see in their own picker. */
  const repSees = () =>
    cy.request('/outlets').then((r) => {
      expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS')
      return (r.body.collection || []).map((o) => o.name)
    })

  /**
   * A user id out of a real login token.
   *
   * Discovered, not hardcoded: seeded ids are not a contract. And the OWNER's own id is what stands in for
   * "a different rep" below — inventing an id that belongs to nobody would test the read rule against a value
   * the screen could never produce.
   */
  const userIdOf = (loginFn) => {
    return cy.request({
      method: 'POST', url: 'http://localhost:8765/api/auth/login',
      headers: { 'Content-Type': 'application/json' },
      body: { email: loginFn, password: 'Demo@2025!' },
    }).then((r) => {
      const t = r.body.data.accessToken.split('.')[1]
      const claims = JSON.parse(atob(t.replace(/-/g, '+').replace(/_/g, '/')))
      const id = Number(claims.userId)
      /*
       * `to.be.a('number')` would NOT catch this: NaN is a number in chai, and `sub` here is the EMAIL, so a
       * fallback chain ending in `sub` would hand back NaN and every later comparison would quietly be false.
       * Assert what is actually required — a real, positive id.
       */
      expect(Number.isFinite(id) && id > 0, `booker userId from token: ${claims.userId}`).to.eq(true)
      return id
    })
  }

  const bookerId = () => userIdOf('booker.marketplace@myplus.com')
  const otherRepId = () => userIdOf('owner.marketplace@myplus.com')

  before(() => {
    cy.loginAsMarketplaceOwner()
    seedOutlet('mine')
    seedOutlet('theirs')
    seedOutlet('free')
  })

  beforeEach(() => cy.loginAsMarketplaceOwner())

  after(() => {
    // Leave no server state behind: return every seeded outlet to the shared pool, so a later run of any
    // spec that reads /outlets is not looking at a territory this one carved out.
    cy.loginAsMarketplaceOwner()
    cy.then(() => assign(null, Object.values(ctx.outlets).map((o) => o.id)))
  })

  it('THE CASE — assigning narrows the rep to their territory, and unassigned outlets stay shared', () => {
    bookerId().then((rep) => {
      // Before: no assignments anywhere, so the rep sees everything. That is the day-one behaviour and also
      // the baseline that makes the narrowing below meaningful.
      cy.loginAsOrderBooker()
      cy.then(repSees).then((names) => {
        expect(names, 'baseline: the rep can see the outlet about to be theirs')
          .to.include(ctx.outlets.mine.name)
        expect(names, 'baseline: and the one about to belong to someone else')
          .to.include(ctx.outlets.theirs.name)
      })

      // Assign one to this rep, and one to a DIFFERENT user (the owner will do — the point is "not this rep").
      cy.loginAsMarketplaceOwner()
      cy.then(() => assign(rep, [ctx.outlets.mine.id]))
        .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
      cy.then(() => otherRepId().then((other) => assign(other, [ctx.outlets.theirs.id])))
        .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      // ── the property: what the REP can see now ────────────────────────────────────────────────────
      cy.loginAsOrderBooker()
      cy.then(repSees).then((names) => {
        // POSITIVE CONTROLS first. Without these, an /outlets that returned nothing at all would satisfy
        // every "is absent" assertion below and this case would go green on a broken endpoint.
        expect(names, 'their own outlet is still there').to.include(ctx.outlets.mine.name)
        expect(names, 'and an UNASSIGNED outlet is still shared').to.include(ctx.outlets.free.name)
        // ...and only then the narrowing.
        expect(names, "someone else's outlet is gone").to.not.include(ctx.outlets.theirs.name)
      })
    })
  })

  it('unassigning returns an outlet to everybody', () => {
    bookerId().then((rep) => {
      cy.then(() => otherRepId().then((other) => assign(other, [ctx.outlets.theirs.id])))
      cy.loginAsOrderBooker()
      cy.then(repSees).then((names) =>
        expect(names, 'held by another rep').to.not.include(ctx.outlets.theirs.name))

      cy.loginAsMarketplaceOwner()
      cy.then(() => assign(null, [ctx.outlets.theirs.id]))
        .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))

      cy.loginAsOrderBooker()
      cy.then(repSees).then((names) =>
        expect(names, 'released back to the shared pool').to.include(ctx.outlets.theirs.name))
    })
  })

  it('a REP cannot assign — and nothing changes when they try', () => {
    /*
     * ROLE_ORDER_BOOKER exists to WITHHOLD: no ADMIN_PRIVILEGE, so a rep cannot confirm their own orders.
     * Handing themselves outlets would undo the same separation from the other end.
     *
     * The refusal alone is not the assertion — the STATE afterwards is. A 403 that had already written the
     * row would pass a status check and still have given away the territory.
     */
    bookerId().then((rep) => {
      cy.loginAsMarketplaceOwner()
      cy.then(() => assign(null, [ctx.outlets.free.id]))     // known clean starting point

      cy.loginAsOrderBooker()
      cy.then(() => assign(rep, [ctx.outlets.free.id])).then((r) => {
        expect(r.body.status, 'a rep is refused').to.not.eq('SUCCESS')
      })

      cy.loginAsMarketplaceOwner()
      cy.then(() => cy.request('/outletAssignments')).then((r) => {
        const row = (r.body.collection || []).filter((o) => o.id === ctx.outlets.free.id)[0]
        expect(row, 'the outlet is still listed').to.exist
        expect(row.assignedRepUserId, 'and it was NOT assigned by the refused call').to.be.oneOf([null, undefined])
      })
    })
  })

  it('the owner sees every outlet with the rep who holds it', () => {
    bookerId().then((rep) => {
      cy.then(() => assign(rep, [ctx.outlets.mine.id]))
      cy.then(() => cy.request('/outletAssignments')).then((r) => {
        expect(r.body.status).to.eq('SUCCESS')
        const rows = r.body.collection || []
        const mine = rows.filter((o) => o.id === ctx.outlets.mine.id)[0]
        const free = rows.filter((o) => o.id === ctx.outlets.free.id)[0]
        expect(mine, 'the assigned outlet is listed').to.exist
        expect(Number(mine.assignedRepUserId), 'with its holder').to.eq(rep)
        expect(free, 'and so is the unassigned one').to.exist
        expect(free.assignedRepUserId, 'reported as held by nobody').to.be.oneOf([null, undefined])
        // Identity only — an assignment screen is not a financial report, same rule as OutletDTO.
        expect(mine, 'no balance rides along').to.not.have.property('dueAmount')
        expect(mine, 'and no credit limit either').to.not.have.property('creditLimit')
      })
    })
  })

  it('a REP cannot read the assignment list at all', () => {
    // Which colleague holds which shop is exactly the disclosure OutletDTO was kept narrow to avoid.
    cy.loginAsOrderBooker()
    cy.request({ url: '/outletAssignments', failOnStatusCode: false }).then((r) => {
      expect(r.body.status, 'refused for a rep').to.not.eq('SUCCESS')
    })
  })

  it("another tenant's outlet cannot be assigned, and the call says how many it really changed", () => {
    /*
     * Every id here comes from the browser. The repository scopes inside its WHERE clause, so a foreign id is
     * simply not updated — and the response reports the rows ACTUALLY changed rather than echoing the count
     * it was sent, which is what makes a partially-ignored request visible instead of silently successful.
     */
    bookerId().then((rep) => {
      // An id from a different tenant: seeded by another org's owner, never visible to this one.
      cy.loginAsBusiness()
      cy.request('/getUserCustomer').then((r) => {
        const foreign = (r.body.collection || r.body.data || [])[0]
        expect(foreign, 'the other tenant has at least one customer to try').to.exist
        const foreignId = foreign.customerId || foreign.id

        cy.loginAsMarketplaceOwner()
        cy.then(() => assign(rep, [ctx.outlets.mine.id, foreignId])).then((res) => {
          expect(res.body.status).to.eq('SUCCESS')
          const body = res.body.object || {}
          expect(Number(body.requested), 'two ids were sent').to.eq(2)
          expect(Number(body.assigned), 'but only the one that was ours was written').to.eq(1)
        })

        // POSITIVE CONTROL for the refusal: prove the mechanism ran at all by checking OUR outlet did move.
        cy.then(() => cy.request('/outletAssignments')).then((r2) => {
          const mine = (r2.body.collection || []).filter((o) => o.id === ctx.outlets.mine.id)[0]
          expect(Number(mine.assignedRepUserId), 'ours was assigned in the same call').to.eq(rep)
        })
      })
    })
  })

  it('DAY ONE — a rep with no assignments still sees every outlet', () => {
    /*
     * The regression most likely to be introduced by this slice and least likely to be noticed: a distributor
     * who has configured no territories at all must go on working exactly as before. If this breaks, their
     * reps open the booking screen to an empty picker and nothing in the logs explains why.
     */
    cy.then(() => assign(null, Object.values(ctx.outlets).map((o) => o.id)))
    cy.loginAsOrderBooker()
    cy.then(repSees).then((names) => {
      expect(names, 'all three seeded outlets are visible').to.include.members(
        Object.values(ctx.outlets).map((o) => o.name))
    })
  })
})
