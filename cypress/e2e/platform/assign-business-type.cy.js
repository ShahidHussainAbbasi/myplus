/**
 * ONB-2 — the operator can SEE which tenants need a business type, and fix them.
 *
 * Design: microservices/docs/slices/onb-2-assign-business-type-design.md
 *
 * ── The problem this gates ──────────────────────────────────────────────────────────────────────
 * 39 of 41 organizations resolve to GENERAL, whose preset is every capability — so a POS counter is shown
 * dispensing screens and a marketplace back-office is shown IMEI. ONB-1 fixed this for NEW tenants; the
 * existing ones need an operator to go and set each one.
 *
 * The control already exists. What was missing is everything that makes using it on 39 tenants practical: the
 * row did not show the shape, and nothing filtered to the ones needing attention.
 *
 * ── ⚠ `general` counts as "needs a business type" ───────────────────────────────────────────────
 * Owner's ruling: `general` stays selectable — it is the honest answer for a genuinely general trader — but it
 * is ALSO how tenants end up showing everything, and only a person can tell the two apart. So the filter counts
 * it, and the label says plainly what it does.
 */

const OPERATOR_PW = Cypress.env('adminPassword') || 'Admin@2025!'

const list = (params) =>
  cy.request({
    method: 'GET',
    url: '/platform/organizations' + (params ? `?${params}` : ''),
    failOnStatusCode: false,
  }).then((r) => {
    expect(r.body && r.body.success, `list: ${JSON.stringify(r.body)}`).to.eq(true)
    return r.body.data
  })

describe('ONB-2 — assigning a business type to existing tenants', () => {
  beforeEach(() => cy.loginAsOperator())

  it('1 — the tenant row shows what kind of business it is', () => {
    /*
     * Without this an operator cannot tell org 20 from org 44 without opening both. At 39 tenants that is the
     * difference between reading a list and clicking through 39 pages.
     */
    cy.visit('/platformDashboard')
    cy.get('[data-testid="tenant-row"]', { timeout: 15000 }).should('have.length.greaterThan', 1)
    // At least one tenant has a real shape (owner.mobile@ is seeded retail), so at least one badge renders.
    cy.get('[data-testid="tenant-shape"]').should('have.length.greaterThan', 0)
  })

  it('⭐ 2 — "Needs a business type" catches BOTH unset and general', () => {
    /*
     * The ruling in one assertion. A tenant on `general` looks like a decision and behaves like an unanswered
     * question; the operator is the only one who can tell which, so the filter must offer them both.
     */
    list('needsType=true&size=100').then((data) => {
      expect(data.rows.length, 'there are tenants needing a type').to.be.greaterThan(0)
      data.rows.forEach((row) => {
        const unset = row.shapeSet === false
        const general = String(row.shape).toLowerCase() === 'general'
        expect(unset || general,
          `${row.name} is in the needs-a-type list but has a real shape: ${JSON.stringify(row)}`).to.eq(true)
      })
    })
  })

  it('3 — the filtered count matches what it returns', () => {
    // A filter that reports a total it does not honour is worse than no filter: the worklist never empties and
    // nobody can tell whether they are done.
    list('needsType=true&size=100').then((filtered) => {
      list('size=100').then((all) => {
        expect(filtered.total, 'the filtered total is smaller than everything').to.be.lessThan(all.total)
        expect(filtered.rows.length, 'and the rows match the total on one page')
          .to.eq(Math.min(filtered.total, 100))
      })
    })
  })

  it('⭐ 4 — assigning a type removes the tenant from the worklist', () => {
    /*
     * The worklist has to empty, or it is a report rather than a tool. Uses a tenant this spec provisions, so
     * no shared fixture is reshaped — a shape change CLEARS the tenant's capability overrides, which on a
     * shared fixture would break whichever spec runs next.
     */
    const stamp = Date.now()
    cy.request({
      method: 'POST', url: '/platform/provisionTenant', form: true, failOnStatusCode: false,
      body: {
        email: `onb2.${stamp}@myplus.com`, firstName: 'ONB2', lastName: 'Test',
        organizationName: `ONB2 Worklist ${stamp}`, plan: 'PRO', userType: 'BUSINESS', shape: 'general',
      },
    }).then((r) => {
      expect(r.body && r.body.success, `provision: ${JSON.stringify(r.body)}`).to.eq(true)
      const orgId = r.body.data.organizationId

      // Provisioned as `general`, so it starts ON the worklist — the before-state is the opposite of the
      // after-state, which is the only arrangement in which the assertion means anything.
      list(`needsType=true&size=100&q=ONB2 Worklist ${stamp}`).then((before) => {
        expect(before.rows.length, 'a general tenant starts on the worklist').to.eq(1)
      })

      cy.request({
        method: 'POST', url: '/platform/shape', form: true, failOnStatusCode: false,
        body: { organizationId: orgId, shape: 'retail', reason: 'ONB-2 gate' },
      }).then((res) => {
        expect(res.body && res.body.success, `assign: ${JSON.stringify(res.body)}`).to.eq(true)
      })

      list(`needsType=true&size=100&q=ONB2 Worklist ${stamp}`).then((after) => {
        expect(after.rows.length, 'and leaves it once assigned').to.eq(0)
      })
    })
  })

  it('5 — "General" says on screen what it actually does', () => {
    /*
     * Renamed in the Shape enum, so the operator console and the tenant's own Configuration screen say the same
     * words. An owner and an operator discussing a tenant on the phone should not be reading different labels
     * for the same choice.
     */
    cy.visit('/platformDashboard')
    cy.get('[data-testid="tenant-row"]', { timeout: 15000 }).first().click()
    cy.get('#platShapeSelect', { timeout: 15000 })
      .find('option[value="general"]')
      .should('contain.text', 'show every feature')
  })
})
