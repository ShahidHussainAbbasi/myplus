/**
 * SRA-2 — the business's SALES REP list, a customer's usual rep, and who may manage them.
 * Design: microservices/docs/slices/sra-sales-by-rep-and-area.md §5 · Test Book section 29 (B1–B5).
 * The territory RULE itself (what a rep sees) is gated in territory-assignment.cy.js; the screen in territory-screen.cy.js.
 *
 * Tenant: owner.marketplace@ (the distributor). Rep: booker.marketplace@ (a plain member — no rep.manage). Another
 * business: owner.business@.
 *
 * Leaves no server state another spec could trip over: reps are deactivated (never deleted, by design) and the
 * booker's login is UNLINKED in after() — a booker left linked to a rep would narrow every booking-screen spec.
 */
const run = String(Date.now()).slice(-6)
const mobile = () => `03${Math.floor(100000000 + Math.random() * 899999999)}`
const list = (body) => {
  for (const k of ['collection', 'data', 'object']) if (Array.isArray(body && body[k])) return body[k]
  return []
}
const ctx = { reps: [] }

const addRep = (body) => cy.request({ method: 'POST', url: '/addSalesRep', form: true, failOnStatusCode: false, body })
const updateRep = (body) => cy.request({ method: 'POST', url: '/updateSalesRep', form: true, failOnStatusCode: false, body })
const reps = () => cy.request('/getSalesReps').then((r) => {
  expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS')
  return list(r.body)
})
const customerNamed = (name) => cy.request('/getUserCustomer').then((r) => list(r.body).find((c) => c.name === name) ?? null)
const saveCustomer = (body) => cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false, body })
const userIdOf = (email) =>
  cy.request({ method: 'POST', url: 'http://localhost:8765/api/auth/login', headers: { 'Content-Type': 'application/json' },
    body: { email, password: 'Demo@2025!' } }).then((r) => {
    const claims = JSON.parse(atob(r.body.data.accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    const id = Number(claims.userId)
    expect(Number.isFinite(id) && id > 0, `userId from token: ${claims.userId}`).to.eq(true)
    return id
  })
/** A refusal from either door: the monolith's permission rule (403) or business-service's own check (FORBIDDEN). */
const refused = (r) => expect(r.status === 403 || (r.body && (r.body.status === 'FORBIDDEN' || r.body.success === false)),
  `refused: ${r.status} ${JSON.stringify(r.body).slice(0, 200)}`).to.eq(true)

describe('SRA-2 — sales reps', () => {
  before(() => {
    cy.loginAsMarketplaceOwner()
    userIdOf('booker.marketplace@myplus.com').then((id) => { ctx.bookerUserId = id })
    // An interrupted run may have left the booker linked; unlink first, so B1 can link them cleanly.
    reps().then((all) => {
      const linked = all.find((x) => Number(x.userId) === ctx.bookerUserId)
      if (linked) updateRep({ id: linked.id, userId: 0 })
    })
  })

  beforeEach(() => cy.loginAsMarketplaceOwner())

  after(() => {
    cy.loginAsMarketplaceOwner()
    cy.then(() => ctx.outletId && cy.request({ method: 'POST', url: '/assignOutlets', form: true, failOnStatusCode: false,
      body: { customerIds: String(ctx.outletId) } }))
    cy.then(() => ctx.reps.forEach((id) => updateRep({ id, userId: 0, status: 'INACTIVE' })))
  })

  // ── B1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  it('B1 — a rep who never logs in, and one who does, are added (on the screen and by the API)', () => {
    addRep({ name: `SRA Irfan ${run}`, phone: '0305-3939495' }).then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      expect(r.body.object.userId, 'no login').to.be.oneOf([null, undefined])
      ctx.irfan = r.body.object.id
      ctx.reps.push(ctx.irfan)
    })
    addRep({ name: `SRA Booker ${run}`, userId: ctx.bookerUserId }).then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      expect(Number(r.body.object.userId), 'linked to the booker\'s login').to.eq(ctx.bookerUserId)
      ctx.bookerRep = r.body.object.id
      ctx.reps.push(ctx.bookerRep)
    })
    // The screen: Territory → Sales reps → Add rep.
    cy.viewport(1400, 900)
    cy.visit('/businessDashboard')
    cy.window().then((w) => w.showTerritory())
    cy.get('#salesRepPanel').should('be.visible')
    // Names sit in editable boxes — cy.contains reads TEXT, not a box's value, so read the values.
    cy.get('#srBody input[data-sr-field="name"]', { timeout: 15000 }).should(($i) => {   // positive control: the list loads
      expect($i.toArray().map((x) => x.value)).to.include(`SRA Irfan ${run}`)
    })
    cy.get('#srNewName').type(`SRA Screen ${run}`)
    cy.get('#srNewPhone').type('0300-1112223')
    cy.get('#srAdd').click()
    cy.get('#srNotice').should('be.visible').and('contain.text', 'Rep added')
    cy.get('#srBody input[data-sr-field="name"]').should(($i) => {
      expect($i.toArray().map((x) => x.value), 'the new rep is listed').to.include(`SRA Screen ${run}`)
    })
    reps().then((all) => { const s = all.find((x) => x.name === `SRA Screen ${run}`); expect(s).to.exist; ctx.reps.push(s.id) })
  })

  // ── B2 ───────────────────────────────────────────────────────────────────────────────────────────────────
  it('B2 — one login cannot be two reps, and a name cannot be used twice', () => {
    addRep({ name: `SRA Booker Two ${run}`, userId: ctx.bookerUserId }).then((r) => {
      expect(r.body.status).to.eq('FAILED')
      expect(r.body.message).to.contain('already linked')
    })
    addRep({ name: `sra irfan ${run}` }).then((r) => {
      expect(r.body.status, 'the same name in lower case').to.eq('FAILED')
      expect(r.body.message).to.contain('already exists')
    })
    reps().then((all) => {
      expect(all.filter((x) => Number(x.userId) === ctx.bookerUserId).length, 'still exactly one rep on that login').to.eq(1)
    })
  })

  // ── B3 ───────────────────────────────────────────────────────────────────────────────────────────────────
  it('B3 — the usual rep is set on the customer form, comes back by NAME on edit, and a rep cannot change it', () => {
    const name = `SRA Sarwar Rep ${run}`
    cy.viewport(1400, 900)
    cy.openSection('CustomerDiv')
    cy.get('#newCustomer').click()
    cy.get('#customerName').type(name)
    cy.get('#contact').type(mobile())
    cy.get(`#customerDefaultRepId option[value="${ctx.irfan}"]`).should('exist')
    cy.get('#customerDefaultRepId').select(String(ctx.irfan), { force: true })
    cy.intercept('POST', '**/addCustomer').as('save')
    cy.get('#addCustomer').click()
    cy.wait('@save').its('response.body.status').should('eq', 'SUCCESS')
    customerNamed(name).then((c) => {
      expect(c.defaultRepId, 'saved with its usual rep').to.eq(ctx.irfan)
      ctx.outletId = c.customerId
      ctx.outlet = c
    })
    // Edit: the picker shows the rep's NAME (editRecord used to relabel the option with the id).
    cy.get('#tableCustomer_filter input, .dataTables_filter input').first().clear().type(name)
    cy.contains('#tableCustomer tbody tr', name, { timeout: 15000 }).find('.js-edit-row').click({ force: true })
    cy.get('#customerDefaultRepId').should('have.value', String(ctx.irfan))
    cy.get('#customerDefaultRepId option:selected').should('have.text', `SRA Irfan ${run}`)

    // A rep (no rep.manage) cannot hand an outlet to themselves — through either door — and nothing changes.
    cy.then(() => {
      // The browser's door: the booker's set cannot even save a customer (the monolith's rule refuses first).
      cy.loginAsOrderBooker()
      saveCustomer({ customerId: ctx.outlet.customerId, name, contact: ctx.outlet.contact, defaultRepId: ctx.bookerRep })
        .then((r) => expect(r.body.status, JSON.stringify(r.body)).to.not.eq('SUCCESS'))
      // The token's door: the SAME save straight to the gateway with the booker's own token — no monolith rule on the
      // way — reaches business-service's OWN check, which is what this case exists to prove.
      cy.request({ method: 'POST', url: 'http://localhost:8765/api/auth/login', headers: { 'Content-Type': 'application/json' },
        body: { email: 'booker.marketplace@myplus.com', password: 'Demo@2025!' } }).then((login) => {
        cy.request({ method: 'POST', url: 'http://localhost:8765/api/business/addCustomer', form: true, failOnStatusCode: false,
          headers: { Authorization: 'Bearer ' + login.body.data.accessToken },
          body: { customerId: ctx.outlet.customerId, name, contact: ctx.outlet.contact, version: ctx.outlet.version,
            defaultRepId: ctx.bookerRep } })
          .then((r) => {
            expect(r.body.status, JSON.stringify(r.body).slice(0, 300)).to.eq('FORBIDDEN')
            expect(r.body.message).to.contain('not allowed to change who covers a customer')
          })
      })
    })
    cy.loginAsMarketplaceOwner()
    customerNamed(name).then((c) => expect(c.defaultRepId, 'still Irfan').to.eq(ctx.irfan))
  })

  // ── B4 ───────────────────────────────────────────────────────────────────────────────────────────────────
  it('B4 — a deactivated rep is not offered for new outlets, but still names the ones they hold', () => {
    updateRep({ id: ctx.irfan, status: 'INACTIVE' }).its('body.status').should('eq', 'SUCCESS')
    cy.request({ method: 'POST', url: '/assignOutlets', form: true, failOnStatusCode: false,
      body: { repId: ctx.irfan, customerIds: String(ctx.outletId) } }).then((r) => {
      expect(r.body.status, 'not given new outlets').to.eq('FAILED')
      expect(r.body.message).to.contain('no longer active')
    })
    cy.viewport(1400, 900)
    cy.visit('/businessDashboard')
    cy.window().then((w) => w.showTerritory())
    cy.get(`#terrRep option[value="${ctx.bookerRep}"]`, { timeout: 15000 }).should('exist')   // positive control
    cy.get(`#terrRep option[value="${ctx.irfan}"]`).should('not.exist')
    cy.contains('#terrBody tr', `SRA Sarwar Rep ${run}`).find('td').eq(3).should('contain.text', `SRA Irfan ${run}`)
    updateRep({ id: ctx.irfan, status: 'ACTIVE' }).its('body.status').should('eq', 'SUCCESS')
  })

  // ── B5 ───────────────────────────────────────────────────────────────────────────────────────────────────
  it('B5 — a rep cannot manage the list (screen AND server), but can read it for the pickers', () => {
    cy.loginAsOrderBooker()
    addRep({ name: `SRA Sneaky ${run}` }).then(refused)
    updateRep({ id: ctx.irfan, name: 'Renamed by a rep' }).then(refused)
    reps().then((all) => {
      expect(all.map((x) => x.name), 'nothing changed').to.include(`SRA Irfan ${run}`).and.not.include(`SRA Sneaky ${run}`)
    })
    cy.visit('/businessDashboard')
    cy.get('body').should('be.visible')
    cy.get('#TerritoryDiv').should('not.exist')
  })

  it('another business sees none of our reps, and cannot rename one by id', () => {
    cy.loginAsOwner()   // owner.business@
    reps().then((all) => expect(all.map((x) => x.id), 'not listed').to.not.include(ctx.irfan))
    updateRep({ id: ctx.irfan, name: 'HIJACKED' }).then((r) => {
      expect(r.body.status).to.eq('FAILED')
      expect(r.body.message).to.contain('Rep not found')
    })
    cy.loginAsMarketplaceOwner()
    reps().then((all) => expect(all.find((x) => x.id === ctx.irfan).name, 'unchanged').to.eq(`SRA Irfan ${run}`))
  })
})
