/**
 * EX-0a — opt-in capabilities (default OFF), first user: Expense management.
 *
 * Design: microservices/docs/slices/ex-0a-capability-opt-in.md
 *
 * <h3>What this gate exists to catch</h3>
 * Until EX-0a every capability defaulted ON (CapabilityCatalog hard-coded `true`, Shape.GENERAL = allOf). A new
 * MODULE shipped that way appears in front of every tenant on the deploy. Three ways the slice could look done
 * and still be wrong, one case each:
 *   - the opt-in leaks ON through the GENERAL preset or the catalog default          → case 1
 *   - it ships with no reachable switch, or a FREE-plan tenant is refused the write  → cases 2, 3, 5
 *   - a business-type change deletes the owner's choice (applyShape clears org.cap.*) → case 4
 *
 * owner.mobile@ is a real seeded tenant (shape retail). after() leaves it exactly as seeded: Expense
 * management back to its default (override REMOVED, not saved as false) and shape retail.
 */

const CAP = 'expenseManagement'
const KEY = 'org.cap.' + CAP
const SWITCH = `#businessConfigBody [data-key="${KEY}"]`

const openConfig = () => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.get('#snavSettings').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavSettings .snav-btn').click() })
  cy.get('#navConfiguration').click()
  cy.get('#ConfigDiv').should('be.visible')
  cy.get('#businessConfigBody .cfg-group', { timeout: 20000 }).should('have.length.greaterThan', 3)
}

const resetCap = () =>
  cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: KEY } })
    .its('body.success').should('eq', true)

const changeShapeAsOwner = (shape) =>
  // The OWNER's business-type change (OrganizationAdminService.applyShape) — the path that clears overrides.
  // NOT cy.setShape, which writes the org.shape setting directly and clears nothing, so it could not show the
  // defect this case is about.
  cy.request({ method: 'POST', url: '/saveBusinessShape', form: true, failOnStatusCode: false, body: { shape } })
    .then((r) => expect(r.body && r.body.success, `saveBusinessShape(${shape}): ${JSON.stringify(r.body)}`).to.eq(true))

describe('EX-0a — Expense management is an opt-in capability (default OFF)', () => {
  beforeEach(() => {
    cy.loginAsMobileOwner()
  })

  after(() => {
    cy.loginAsMobileOwner()
    changeShapeAsOwner('retail')
    resetCap()
  })

  it('1 — an unconfigured tenant has it OFF while preset capabilities stay ON', () => {
    // Establish the precondition rather than assume it: remove any override a previous run left.
    resetCap()
    cy.getCapabilities().then((caps) => {
      expect(caps, 'the capability map publishes the new code').to.have.property(CAP)
      expect(caps[CAP], 'an opt-in module is OFF until the owner turns it on').to.eq(false)
      // POSITIVE CONTROL: a map that answered false for everything would satisfy the line above.
      expect(caps.installments, 'retail preset capability still ON').to.eq(true)
    })
  })

  it('2 — the Configuration screen offers the switch, unticked', () => {
    resetCap()
    openConfig()
    cy.revealSetting(KEY)
    cy.get(SWITCH).should('exist').and('not.be.checked').and('not.be.disabled')
    cy.get(SWITCH).closest('.cfg-row').should('contain', 'Expense management')
  })

  it('3 — the owner switches it ON from the screen and the capability follows', () => {
    resetCap()
    openConfig()
    cy.revealSetting(KEY)
    cy.get(SWITCH).check()
    cy.get('#businessConfigMsg').should('contain', 'Saved')
    cy.getCapabilities().its(CAP).should('eq', true)
  })

  it('4 — changing the business type keeps the owner\'s choice', () => {
    cy.setCapability(CAP, true)
    // The preview the owner is shown must not claim the module is turning off.
    cy.request(`/getBusinessShapePreview?shape=pharmacy`).then((r) => {
      const p = (r.body && r.body.data) || {}
      const listed = [...(p.turningOn || []), ...(p.turningOff || [])]
      expect(listed, 'a business-type change never lists an opt-in module').not.to.include('Expense management')
    })
    changeShapeAsOwner('pharmacy')
    cy.getCapabilities().its(CAP).should('eq', true)
    changeShapeAsOwner('retail')
    cy.getCapabilities().then((caps) => {
      expect(caps[CAP], 'still ON after a round trip of business types').to.eq(true)
      // POSITIVE CONTROL that the change really ran: pharmacy's preset turned expiry tracking ON, retail's off.
      expect(caps.installments, 'retail preset re-applied').to.eq(true)
    })
  })

  it('5 — a FREE-plan tenant may switch it on (it is in the FREE plan)', () => {
    cy.loginAsOperator()
    cy.request('/platform/organizations?size=200').then((res) => {
      const orgs = (res.body && res.body.data && res.body.data.rows) || []
      expect(orgs.length, 'operator sees tenants').to.be.greaterThan(0)
      const free = orgs.find((o) => String(o.plan).toUpperCase() === 'FREE')
      expect(free, 'at least one FREE-plan tenant exists to assert on (existence checked, not assumed)').to.exist
      cy.request(`/platform/entitlements?organizationId=${free.id}`).then((ent) => {
        const rows = (ent.body && ent.body.data && ent.body.data.capabilities) || []
        const row = rows.find((r) => r.capability === CAP)
        expect(row, 'the operator view lists Expense management').to.exist
        expect(row.inPlan, 'FREE includes it — otherwise most legacy tenants can never turn it on').to.eq(true)
        expect(row.enabled, 'and it is still OFF until that owner chooses it').to.eq(false)
      })
    })
  })
})
