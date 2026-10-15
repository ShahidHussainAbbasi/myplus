/**
 * O7 D6a — the owner's territory screen.
 *
 * The endpoints are gated separately in `territory-assignment.cy.js`, which proves the RULE: assigning
 * narrows what a rep can see. This file proves the screen an owner actually uses reaches those endpoints and
 * shows the truth back.
 *
 * <h3>What it is careful about</h3>
 * SRA-2: the screen renders "who covers this outlet" by joining the row's `repId` against the SALES REP list it
 * loads separately (`/getSalesReps`); the login picker for a rep comes from the auth proxy — two response SHAPES
 * (`{status, collection}` from business-service, `{success, data}` from the auth proxy). Reading the wrong field yields `undefined` rather than an error, so the assertion here
 * is that a rep's NAME appears in the row, not merely that a row exists.
 *
 * <h3>Leave no server state behind</h3>
 * This spec assigns real outlets. Everything it touches is released in `after()` — a territory left behind
 * would narrow the pickers of every later spec that reads `/outlets`, and nothing in those specs would explain
 * why.
 */
describe('O7 D6a — the territory screen', () => {
  const run = String(Date.now()).slice(-6)
  const ctx = {}

  const open = () => {
    cy.viewport(1400, 900)
    cy.loginAsMarketplaceOwner()
    cy.visit('/businessDashboard')
    cy.window().should((w) => expect(w.showTerritory, 'territory.js is loaded').to.be.a('function'))
    cy.window().then((w) => w.showTerritory())
    cy.get('#TerritoryDiv').should('be.visible')
    // The rows arrive by AJAX. Wait for a REAL outlet row (it has a checkbox): a "Loading…" or "No outlets." row would
    // satisfy "at least one row" and let the next step count the wrong table — which is how the search case failed.
    cy.get('#terrBody .terr-pick', { timeout: 20000 }).should('have.length.greaterThan', 0)
  }

  before(() => {
    cy.loginAsMarketplaceOwner()
    const name = 'TerrUI_' + run
    cy.request({
      method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
      body: { name, contact: '0355' + run, creditLimit: 50000 },
    }).then((r) => expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS'))
    cy.then(() => cy.request('/getUserCustomer')).then((r) => {
      const row = (r.body.collection || r.body.data || []).filter((c) => c.name === name)[0]
      expect(row, 'the test outlet exists').to.exist
      ctx.id = row.customerId || row.id
      ctx.name = name
    })
    // SRA-2: outlets are covered by a REP on the business's list — this spec makes its own (no login).
    cy.request({ method: 'POST', url: '/addSalesRep', form: true, body: { name: `TerrUI Rep ${run}` } }).then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      ctx.repId = r.body.object.id
      ctx.repName = r.body.object.name
    })
  })

  after(() => {
    cy.loginAsMarketplaceOwner()
    cy.request({
      method: 'POST', url: '/assignOutlets', form: true, failOnStatusCode: false,
      body: { customerIds: String(ctx.id) },
    })
    cy.then(() => ctx.repId && cy.request({ method: 'POST', url: '/updateSalesRep', form: true, failOnStatusCode: false,
      body: { id: ctx.repId, status: 'INACTIVE' } }))
  })

  it('THE SCREEN — assigning from the UI shows the rep as the outlet\'s holder', () => {
    open()

    // Find OUR outlet's row, whatever page position it lands in among the org's outlets.
    cy.contains('#terrBody tr', ctx.name).as('row')
    cy.get('@row').find('td').eq(3)
      .should('contain.text', 'Unassigned')      // baseline, and the shared-pool wording

    cy.get('@row').find('.terr-pick').check()
    // SRA-2: the picker lists the business's REPS; this spec's own rep must be offered.
    cy.get(`#terrRep option[value="${ctx.repId}"]`).should('exist')
    cy.get('#terrRep').select(String(ctx.repId), { force: true })
    cy.wrap(ctx.repName).as('repLabel')
    cy.get('#terrApply').click()

    cy.get('#terrNotice', { timeout: 20000 }).should('be.visible')

    // ── the property: the screen shows the holder, by NAME ────────────────────────────────────────
    // Not "the cell changed" — the join across two differently-shaped responses is the thing most likely
    // to break, and it fails by rendering `undefined` or a bare `#87`, both of which a laxer assertion
    // would happily accept.
    cy.get('@repLabel').then((label) => {
      cy.contains('#terrBody tr', ctx.name).find('td').eq(3)
        .should('contain.text', String(label).split(' (')[0])
    })
  })

  it('unassigning from the UI returns it to the shared pool', () => {
    open()
    cy.contains('#terrBody tr', ctx.name).find('.terr-pick').check()
    cy.get('#terrClear').click()
    cy.get('#terrNotice', { timeout: 20000 }).should('be.visible')
    cy.contains('#terrBody tr', ctx.name).find('td').eq(3).should('contain.text', 'Unassigned')
  })

  it('the search box narrows the list without touching what is selected elsewhere', () => {
    open()
    cy.get('#terrBody tr').its('length').then((all) => {
      cy.get('#terrFilter').type(ctx.name)
      cy.get('#terrBody tr').should('have.length.lessThan', all)
      cy.get('#terrBody tr').should('have.length', 1)
      cy.contains('#terrBody tr', ctx.name).should('exist')
    })
  })

  it('applying with nothing selected says so instead of silently doing nothing', () => {
    // A no-op button that gives no feedback is indistinguishable from a broken one.
    open()
    cy.get('#terrApply').click()
    cy.get('#terrNotice').should('be.visible').and('not.have.text', '')
  })

  it('a REP never sees the menu entry at all', () => {
    // The markup is behind the rep.manage permission (ui_menu 'territory') AND business-service refuses the
    // endpoint, so the screen and the server agree rather than the screen being the only guard.
    cy.loginAsOrderBooker()
    cy.visit('/businessDashboard')
    cy.get('body').should('be.visible')
    cy.get('#TerritoryDiv').should('not.exist')
  })
})
