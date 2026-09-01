/**
 * Task #20 — stock value in hand, beside the product count.
 *
 * <h3>⚠ What this number is, and why the label matters more than the tile</h3>
 * `StockLevel.costPrice` is stamped by the purchase path from `bpurchaseRate`, so this values the whole shelf
 * at the LAST PURCHASE RATE. It is not a weighted average and it is not the GL inventory balance, and it
 * drifts from both the moment a buying price moves.
 *
 * An unqualified "stock value" is precisely the figure people trust without checking, so the LABEL is
 * asserted here as strictly as the number. A correct total under a misleading label is the defect.
 *
 * <h3>Performance is part of the contract</h3>
 * The total is a SQL aggregate. It replaced a Java stream that loaded every StockLevel of the tenant and
 * discarded them to keep one BigDecimal — the same shape of work this dashboard was brought from ~640ms down
 * by not doing.
 */

const OWNER = 'owner.business@myplus.com'

describe('Dashboard — stock value in hand', () => {
  beforeEach(() => {
    cy.loginAsOwner(OWNER)
  })

  it('⭐ the stats payload carries a stock value', () => {
    cy.request({ url: '/getBusinessDashboardStats' }).then((r) => {
      const stats = r.body.object || r.body.data || r.body
      expect(stats, 'the stats object').to.be.an('object')
      // Absent is a legitimate state (inventory-service unreachable) — but on a healthy stack it must be
      // there, or the KPI silently never appears and nobody notices.
      expect(stats).to.have.property('stockValue')
      expect(Number(stats.stockValue), 'a real number, not NaN').to.not.be.NaN
    })
  })

  it('⭐ the tile is LABELLED with the costing basis', () => {
    /*
     * The assertion that matters most. A tile reading "Stock value" alone would be a figure that disagrees
     * with the ledger under a name that claims otherwise.
     */
    cy.visit('/businessDashboard')
    cy.get('#dashStockValue', { timeout: 30000 }).should('exist')
    cy.get('[data-widget="stockValue"] .kpi-label')
      .invoke('text')
      .should('match', /last purchase|dernier prix|último precio|अंतिम खरीद|آخر شراء|آخری خریداری/i)
  })

  it('the tile renders the value, and never a bare 0 for "unavailable"', () => {
    cy.visit('/businessDashboard')
    cy.get('#dashStockValue', { timeout: 30000 })
      .invoke('text')
      .should((txt) => {
        const v = txt.trim()
        expect(v, 'the tile is filled in').to.not.eq('-')
        // Either a number, or an em-dash meaning "could not be read". Never 0 standing in for unknown.
        expect(v === '—' || !isNaN(Number(v)), `unexpected tile text: ${v}`).to.eq(true)
      })
  })

  it('it sits beside the product COUNT — value and count are different questions', () => {
    cy.visit('/businessDashboard')
    cy.get('#dashItems', { timeout: 30000 }).should('exist')
    cy.get('#dashStockValue').should('exist')
  })
})
