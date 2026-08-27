/**
 * C5 — the dashboard grid as a declared widget set.
 *
 * Three claims, and the second is the one that makes this more than cosmetics:
 *
 *   1. A capability-gated widget appears only for a tenant that has the capability.
 *   2. **The server does not compute its data for anyone else** — the key is ABSENT from the stats payload,
 *      not zero. A hidden tile whose data was fetched anyway is a much weaker claim, and on this screen it
 *      is also a performance regression: the dashboard was brought from ~3s to ~0.27s by removing exactly
 *      that kind of unconditional work.
 *   3. A tenant that switched a capability on sees its widgets FIRST. A mobile shop should not find "On
 *      terms" seventh, behind Companies.
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
    })
  })

  // ── OFF ─────────────────────────────────────────────────────────────────────────────────────────

  it('OFF — the widget is hidden AND the server never computes its data', () => {
    cy.setCapability(CAP, false)

    /*
     * ⭐ The payload assertion is the point of this test.
     *
     * "The tile is hidden" only proves the DOM was tidied. This proves the tenant is not PAYING for a widget
     * they cannot see — the key is absent, so the COUNT query never ran. That is the difference between a
     * capability that gates a feature and one that only gates its appearance.
     */
    stats().then((s) => {
      expect(s, 'no capability, no query, no key').to.not.have.property('installmentsDue')
      // Positive control on the same payload: the generic counts are still there, so the assertion above is
      // not passing because the whole endpoint broke.
      expect(s, 'the rest of the dashboard is unaffected').to.have.property('monthlyRevenue')
    })

    cy.visit('/businessDashboard')
    cy.get(WIDGET).should('have.class', 'cap-off').and('not.be.visible')
    // And the generic tiles are still on screen — a build that hid EVERYTHING would pass the line above.
    cy.get('[data-widget="monthlyRevenue"]').should('not.have.class', 'cap-off').and('be.visible')
  })
})
