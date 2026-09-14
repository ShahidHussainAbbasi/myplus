/**
 * C5 — the dashboard grid as a declared widget set.
 *
 * Three claims, and the second is the one that makes this more than cosmetics:
 *
 *   1. A capability-gated widget appears only for a tenant that has the capability.
 *   2. **The capability governs the TILE, never the tenant's own figures** — the stats payload carries
 *      `installmentsDue` for a tenant with open plans whether the capability is on or off.
 *   3. A tenant that switched a capability on sees its widgets FIRST. A mobile shop should not find "On
 *      terms" seventh, behind Companies.
 *
 * ⚠ CLAIM 2 IS THE OPPOSITE OF WHAT THIS FILE FIRST ASSERTED, and the reversal was deliberate.
 *
 * It used to require the key to be ABSENT without the capability — "no capability, no query, no key" — on
 * the reasoning that a hidden tile must not be paid for. ONB-2 overturned that, and the controller states
 * why: InstallmentController's endpoints gate on nothing, because a customer's debt does not evaporate
 * because a shop changed trade. A shop that switched away from installments kept collectable plans while
 * the amount outstanding vanished from its dashboard, with nothing left to remind it.
 *
 *     THE RULE: a capability governs what a tenant may DO NEXT, never what they may SEE about what
 *     they have already done.
 *
 * The performance claim survives in a narrower form: the count is emitted only when there is something to
 * count (`if (openPlans > 0)`), so a tenant that never sold on terms still gets nothing and the tile, which
 * keeps its own data-capability, never renders for them.
 *
 * Plus a structural check that the registry and the markup have not drifted apart, which is the failure that
 * would otherwise be discovered by a widget silently never being ordered.
 */

const OWNER = 'owner.business@myplus.com'
const CAP = 'installments'
const WIDGET = '[data-widget="installmentsDue"]'

const stats = () => cy.request('/getBusinessDashboardStats').then((r) => r.body.object)

describe('C5 — dashboard widgets', () => {
  beforeEach(() => {
    // testIsolation clears the session, so authed cy.request needs the login re-established.
    cy.loginAsOwner(OWNER)
  })

  after(() => {
    // Leave no server state behind — a capability left off changes every later spec's dashboard.
    cy.loginAsOwner(OWNER)
    cy.setCapability(CAP, true)
  })

  // ── the inventory ───────────────────────────────────────────────────────────────────────────────

  it('every widget in the markup is in the registry', () => {
    /*
     * The drift this catches: someone adds a tile to businessDashboard.html, gives it data-widget, and never
     * registers it. Nothing breaks visibly — the widget simply never participates in ordering and sits
     * wherever the template happened to put it, which looks like the ordering rule is broken rather than
     * like the widget is missing from a list.
     */
    cy.setCapability(CAP, true)
    cy.visit('/businessDashboard')
    cy.window().then((w) => {
      expect(w.DashboardWidgets, 'the registry is loaded').to.be.an('object')
      const registered = w.DashboardWidgets.all().map((d) => d.name)
      const inMarkup = Array.from(w.document.querySelectorAll('[data-widget]'))
        .map((el) => el.getAttribute('data-widget'))

      expect(inMarkup.length, 'the dashboard has widgets to order').to.be.greaterThan(5)
      inMarkup.forEach((name) => {
        expect(registered, `[data-widget="${name}"] exists in the markup but is not registered`)
          .to.include(name)
      })
    })
  })

  // ── ON ──────────────────────────────────────────────────────────────────────────────────────────

  it('ON — the widget is shown, its data is served, and it leads its row', () => {
    cy.setCapability(CAP, true)
    cy.visit('/businessDashboard')

    cy.get(WIDGET).should('exist').and('not.have.class', 'cap-off').and('be.visible')
    // A tile that renders "-" or "undefined" is present and useless; assert it carries a real number.
    cy.get('#dashInstallmentsDue').invoke('text').should('match', /^\d+$/)

    // The capability-specific widget sorts ahead of the generic counts in the same row.
    cy.window().then((w) => {
      const el = w.document.querySelector('[data-widget="installmentsDue"]')
      const siblings = Array.from(el.parentNode.querySelectorAll('[data-widget]'))
      expect(siblings.indexOf(el), 'a capability a tenant switched ON leads its row').to.eq(0)
    })

    stats().then((s) => {
      expect(s, 'the server computes the count when the capability is on').to.have.property('installmentsDue')
      // Not vacuous: a zero-plan tenant would legitimately carry no key at all, and then the OFF case
      // below would be comparing two absences and proving nothing.
      expect(Number(s.installmentsDue), 'this tenant actually has open plans to count').to.be.greaterThan(0)
    })
  })

  // ── OFF ─────────────────────────────────────────────────────────────────────────────────────────

  it("OFF — the widget is hidden, but the tenant's own figures are NOT taken away", () => {
    /*
     * ⭐ THE ONB-2 RULE, asserted as an EQUALITY rather than an absence.
     *
     * The capability decides whether the tile renders. It does not decide whether the shop is told what it
     * is still owed — that was the defect ONB-2 fixed, and a shop that switched trade kept collectable
     * plans while the figure disappeared from its dashboard.
     *
     * Comparing the two payloads is what makes this checkable without seeding a second tenant: the figure
     * must be the SAME with the capability on and off. An absence-assertion would need a tenant with zero
     * open plans, and "existence is not eligibility" — this tenant has 299, so a test written that way
     * could only ever have been wrong about which rule it was measuring.
     */
    cy.setCapability(CAP, true)
    stats().then((withCap) => {
      const before = withCap.installmentsDue
      expect(before, 'the ON payload carries a figure to compare against').to.exist

      cy.setCapability(CAP, false)
      stats().then((s) => {
        expect(s, 'switching the capability OFF does not take the figure away')
          .to.have.property('installmentsDue')
        expect(s.installmentsDue, 'and it is the SAME figure — the capability moved the tile, not the books')
          .to.eq(before)
        // Positive control on the same payload: the generic counts are still there, so the assertions above
        // are not passing because the whole endpoint broke.
        expect(s, 'the rest of the dashboard is unaffected').to.have.property('monthlyRevenue')
      })
    })

    cy.visit('/businessDashboard')
    cy.get(WIDGET).should('have.class', 'cap-off').and('not.be.visible')
    // And the generic tiles are still on screen — a build that hid EVERYTHING would pass the line above.
    cy.get('[data-widget="monthlyRevenue"]').should('not.have.class', 'cap-off').and('be.visible')
  })
})
