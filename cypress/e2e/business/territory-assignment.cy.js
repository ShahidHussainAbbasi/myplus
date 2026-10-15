/**
 * O7 D6a, rebuilt on SALES REPS (SRA-2) — a territory is what a rep can see.
 *
 * <h3>What changed in SRA-2</h3>
 * D6a pointed an outlet at a LOGIN (`assigned_rep_user_id`). SRA-2 points it at a REP on the business's own list
 * (`customer.default_rep_id`): a rep is a person, with an optional login. The rule:
 *
 * <pre>
 *   a manager of reps (rep.manage — the owner and admins)  → every outlet
 *   a rep (their login is linked to a rep)                 → their outlets + every unassigned outlet
 *   anyone else                                            → every outlet (day one)
 * </pre>
 *
 * <h3>Why this gate was red from 16 Sep</h3>
 * The endpoints asked `seesAllOrg()` — the permission set's "Sees: All" ROW scope — where they meant "may this person
 * manage territories". A booker whose set had scope ALL was never narrowed and could assign. SRA-2 asks the rep.manage
 * permission for managing, and "is this login a rep" for narrowing. (The likely cause; it was never confirmed by
 * reading the booker's set in the database.)
 *
 * <h3>The positive control</h3>
 * "The rep's list got shorter" would pass just as well against an endpoint returning nothing at all. Every narrowing
 * assertion here names an outlet that MUST still be present alongside the one that must be gone.
 *
 * <h3>Leaves no server state</h3>
 * after() unassigns every seeded outlet, UNLINKS the booker's login from the test rep and deactivates both test reps —
 * a booker left linked to a rep would narrow every other spec that opens the booking screen.
 */
describe('O7 D6a / SRA-2 — a territory is what a rep can see', () => {
  const run = String(Date.now()).slice(-6)
  const ctx = { outlets: {} }
  const list = (body) => {
    for (const k of ['collection', 'data', 'object']) if (Array.isArray(body && body[k])) return body[k]
    return []
  }

  /** Seed an outlet as the owner and remember its id. */
  const seedOutlet = (label) => {
    const name = 'Terr_' + label + '_' + run
    cy.request({
      method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name, contact: '03' + run + label.length, creditLimit: 50000 },
    }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    cy.then(() => cy.request('/getUserCustomer')).then((r) => {
      const rows = list(r.body).filter((c) => c.name === name)
      expect(rows.length, `${name} created exactly once`).to.eq(1)
      ctx.outlets[label] = { id: rows[0].customerId || rows[0].id, name }
    })
  }

  /** repId null = UNASSIGN (the shared pool). */
  const assign = (repId, ids, opts = {}) =>
    cy.request({
      method: 'POST', url: '/assignOutlets', form: true, failOnStatusCode: false,
      body: { ...(repId == null ? {} : { repId }), customerIds: ids.join(',') },
      ...opts,
    })

  /** The names the signed-in person can see in the booking screen's picker. */
  const repSees = () =>
    cy.request('/outlets').then((r) => {
      expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS')
      return (r.body.collection || []).map((o) => o.name)
    })

  const assignmentOf = (id) => cy.request('/outletAssignments').then((r) => {
    expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS')
    return list(r.body).find((o) => o.id === id) ?? null
  })

  /** A user id out of a real login token (discovered, never hardcoded). */
  const userIdOf = (email) =>
    cy.request({
      method: 'POST', url: 'http://localhost:8765/api/auth/login',
      headers: { 'Content-Type': 'application/json' },
      body: { email, password: 'Demo@2025!' },
    }).then((r) => {
      const t = r.body.data.accessToken.split('.')[1]
      const claims = JSON.parse(atob(t.replace(/-/g, '+').replace(/_/g, '/')))
      const id = Number(claims.userId)
      expect(Number.isFinite(id) && id > 0, `userId from token: ${claims.userId}`).to.eq(true)
      return id
    })

  const reps = () => cy.request('/getSalesReps').then((r) => list(r.body))

  before(() => {
    cy.loginAsMarketplaceOwner()
    seedOutlet('mine')
    seedOutlet('theirs')
    seedOutlet('free')
    userIdOf('booker.marketplace@myplus.com').then((bookerUserId) => {
      ctx.bookerUserId = bookerUserId
      // A rep already linked to the booker (left by an interrupted run) is REUSED — one login is at most one rep.
      return reps().then((all) => {
        const linked = all.find((x) => Number(x.userId) === bookerUserId)
        if (linked) {
          ctx.bookerRepId = linked.id
          return cy.request({ method: 'POST', url: '/updateSalesRep', form: true, body: { id: linked.id, status: 'ACTIVE' } })
        }
        return cy.request({ method: 'POST', url: '/addSalesRep', form: true,
          body: { name: `Terr Booker ${run}`, userId: bookerUserId } })
          .then((r) => { expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'); ctx.bookerRepId = r.body.object.id })
      })
    })
    cy.request({ method: 'POST', url: '/addSalesRep', form: true, body: { name: `Terr Other ${run}` } })
      .then((r) => { expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'); ctx.otherRepId = r.body.object.id })
  })

  beforeEach(() => cy.loginAsMarketplaceOwner())

  after(() => {
    cy.loginAsMarketplaceOwner()
    cy.then(() => assign(null, Object.values(ctx.outlets).map((o) => o.id)))
    cy.then(() => ctx.bookerRepId && cy.request({ method: 'POST', url: '/updateSalesRep', form: true, failOnStatusCode: false,
      body: { id: ctx.bookerRepId, userId: 0, status: 'INACTIVE' } }))
    cy.then(() => ctx.otherRepId && cy.request({ method: 'POST', url: '/updateSalesRep', form: true, failOnStatusCode: false,
      body: { id: ctx.otherRepId, status: 'INACTIVE' } }))
  })

  it('THE CASE — assigning narrows the rep to their territory, and unassigned outlets stay shared', () => {
    cy.loginAsOrderBooker()
    cy.then(repSees).then((names) => {
      expect(names, 'baseline: the rep can see the outlet about to be theirs').to.include(ctx.outlets.mine.name)
      expect(names, 'baseline: and the one about to belong to someone else').to.include(ctx.outlets.theirs.name)
    })

    cy.loginAsMarketplaceOwner()
    cy.then(() => assign(ctx.bookerRepId, [ctx.outlets.mine.id])).its('body.status').should('eq', 'SUCCESS')
    cy.then(() => assign(ctx.otherRepId, [ctx.outlets.theirs.id])).its('body.status').should('eq', 'SUCCESS')

    cy.loginAsOrderBooker()
    cy.then(repSees).then((names) => {
      // POSITIVE CONTROLS first.
      expect(names, 'their own outlet is still there').to.include(ctx.outlets.mine.name)
      expect(names, 'and an UNASSIGNED outlet is still shared').to.include(ctx.outlets.free.name)
      expect(names, "someone else's outlet is gone").to.not.include(ctx.outlets.theirs.name)
    })
  })

  it('unassigning returns an outlet to everybody', () => {
    cy.then(() => assign(ctx.otherRepId, [ctx.outlets.theirs.id]))
    cy.loginAsOrderBooker()
    cy.then(repSees).then((names) => expect(names, 'held by another rep').to.not.include(ctx.outlets.theirs.name))

    cy.loginAsMarketplaceOwner()
    cy.then(() => assign(null, [ctx.outlets.theirs.id])).its('body.status').should('eq', 'SUCCESS')

    cy.loginAsOrderBooker()
    cy.then(repSees).then((names) => expect(names, 'released back to the shared pool').to.include(ctx.outlets.theirs.name))
  })

  it('a REP cannot assign — and nothing changes when they try', () => {
    cy.then(() => assign(null, [ctx.outlets.free.id]))     // known clean starting point
    cy.loginAsOrderBooker()
    cy.then(() => assign(ctx.bookerRepId, [ctx.outlets.free.id])).then((r) => {
      expect(r.body.status, 'a rep is refused').to.not.eq('SUCCESS')
    })
    cy.loginAsMarketplaceOwner()
    assignmentOf(ctx.outlets.free.id).then((row) => {
      expect(row, 'the outlet is still listed').to.exist
      expect(row.repId, 'and it was NOT assigned by the refused call').to.be.oneOf([null, undefined])
    })
  })

  it('the owner sees every outlet with the rep who holds it', () => {
    cy.then(() => assign(ctx.bookerRepId, [ctx.outlets.mine.id]))
    assignmentOf(ctx.outlets.mine.id).then((mine) => {
      expect(mine, 'the assigned outlet is listed').to.exist
      expect(Number(mine.repId), 'with its holder').to.eq(ctx.bookerRepId)
      // Identity only — an assignment screen is not a financial report, same rule as OutletDTO.
      expect(mine, 'no balance rides along').to.not.have.property('dueAmount')
      expect(mine, 'and no credit limit either').to.not.have.property('creditLimit')
    })
    cy.then(() => assign(null, [ctx.outlets.free.id]))
    assignmentOf(ctx.outlets.free.id).then((free) => {
      expect(free, 'and so is the unassigned one').to.exist
      expect(free.repId, 'reported as held by nobody').to.be.oneOf([null, undefined])
    })
  })

  it('a REP cannot read the assignment list at all', () => {
    cy.loginAsOrderBooker()
    cy.request({ url: '/outletAssignments', failOnStatusCode: false }).then((r) => {
      expect(r.body.status, 'refused for a rep').to.not.eq('SUCCESS')
    })
  })

  it("another tenant's outlet cannot be assigned, and the call says how many it really changed", () => {
    cy.loginAsBusiness()
    cy.request('/getUserCustomer').then((r) => {
      const foreign = list(r.body)[0]
      expect(foreign, 'the other tenant has at least one customer to try').to.exist
      const foreignId = foreign.customerId || foreign.id

      cy.loginAsMarketplaceOwner()
      cy.then(() => assign(ctx.bookerRepId, [ctx.outlets.mine.id, foreignId])).then((res) => {
        expect(res.body.status).to.eq('SUCCESS')
        const body = res.body.object || {}
        expect(Number(body.requested), 'two ids were sent').to.eq(2)
        expect(Number(body.assigned), 'but only the one that was ours was written').to.eq(1)
      })
      // POSITIVE CONTROL: our outlet did move in the same call.
      assignmentOf(ctx.outlets.mine.id).then((mine) => expect(Number(mine.repId), 'ours was assigned').to.eq(ctx.bookerRepId))
    })
  })

  it("another business's REP cannot be given our outlets", () => {
    // SRA-2 anti-IDOR: the rep id is resolved inside the caller's business. owner.business@ — another business's owner.
    cy.loginAsOwner()
    cy.request({ method: 'POST', url: '/addSalesRep', form: true, failOnStatusCode: false, body: { name: `Foreign Rep ${run}` } })
      .then((r) => {
        expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
        const foreignRepId = r.body.object.id
        cy.request({ method: 'POST', url: '/updateSalesRep', form: true, failOnStatusCode: false,
          body: { id: foreignRepId, status: 'INACTIVE' } })
        cy.loginAsMarketplaceOwner()
        cy.then(() => assign(foreignRepId, [ctx.outlets.free.id])).then((res) => {
          expect(res.body.status, JSON.stringify(res.body)).to.eq('FAILED')
          expect(res.body.message).to.contain('rep was not found')
        })
        assignmentOf(ctx.outlets.free.id).then((free) => expect(free.repId, 'unchanged').to.be.oneOf([null, undefined]))
      })
  })

  it('DAY ONE — a rep with no assignments still sees every outlet', () => {
    cy.then(() => assign(null, Object.values(ctx.outlets).map((o) => o.id)))
    cy.loginAsOrderBooker()
    cy.then(repSees).then((names) => {
      expect(names, 'all three seeded outlets are visible').to.include.members(Object.values(ctx.outlets).map((o) => o.name))
    })
  })
})
