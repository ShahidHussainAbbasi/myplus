/**
 * UI-DASH-1 — the business dashboard's KPI tiles (data-widget="companies", "venders", … — .kpi-card).
 *
 * Review 2026-09-26, measured before the fix: 8 tiles in col-sm-2 = a ragged 6 + 2; tiles 98px wide on a 1024px
 * tablet with their numbers cut to one digit; heights 93-160px within one row; "3704" shown as "370"; raw figures
 * ("165710"); and "On Terms" stuck on its loading "-" for a tenant with no open plans.
 *
 * Geometry is read off the screen, so it judges any markup by the same rule.
 *
 * Run headed.
 */
const SIZES = [
  { name: 'desktop', w: 1366, h: 900, perRow: 4 },
  { name: 'tablet', w: 1024, h: 900, perRow: 4 },
  { name: 'phone', w: 390, h: 900, perRow: 2 },
]

const tiles = (w) => [...w.document.querySelectorAll('#DashboardDiv [data-widget] > .kpi-card')]
  .filter((t) => t.offsetParent !== null)

function openDashboard() {
  cy.intercept('GET', '**/getBusinessDashboardStats*').as('stats')
  cy.visit('/businessDashboard')
  cy.wait('@stats')
  cy.waitForAppReady()
}

describe('UI-DASH-1 — dashboard KPI tiles', () => {
  beforeEach(() => { cy.loginAsBusiness() })   // demo.business@ holds installments, so all 8 tiles show

  SIZES.forEach(({ name, w: width, h, perRow }) => {
    it(`${name} ${width}px: ${perRow} tiles per row, equal heights, no figure clipped`, () => {
      cy.viewport(width, h)
      openDashboard()
      cy.get('#dashCompanies').should(($v) => expect($v.text()).to.not.eq('-'))
      cy.window().then((w) => {
        const ts = tiles(w)
        expect(ts.length, 'visible KPI tiles').to.be.at.least(7)
        // rows by top edge
        const rows = {}
        ts.forEach((t) => { const top = Math.round(t.getBoundingClientRect().top); (rows[top] = rows[top] || []).push(t) })
        const tops = Object.keys(rows).map(Number).sort((a, b) => a - b)
        tops.forEach((top, i) => {
          const row = rows[top]
          if (i < tops.length - 1) expect(row.length, `row ${i + 1} is full`).to.eq(perRow)
          const hs = row.map((t) => Math.round(t.getBoundingClientRect().height))
          expect(Math.max(...hs) - Math.min(...hs), `row ${i + 1} tiles are one height (${hs})`).to.be.at.most(1)
        })
        ts.forEach((t) => {
          const v = t.querySelector('.kpi-value')
          expect(v.scrollWidth, `${v.id} "${v.textContent}" is not clipped`).to.be.at.most(v.clientWidth + 1)
          expect(t.getBoundingClientRect().width, `${v.id}: a usable tile width`).to.be.at.least(140)
        })
      })
    })
  })

  it('figures are grouped, with the exact value in the tooltip', () => {
    openDashboard()
    cy.get('#dashItems').should(($v) => {
      const txt = $v.text().trim()
      const n = Number(txt.replace(/[^\d]/g, ''))
      if (n >= 1000) expect(txt, 'a count of thousands is grouped').to.match(/\d[,. ٬]\d{3}/)
      expect($v.attr('title'), 'the exact figure is one hover away').to.be.a('string').and.not.be.empty
    })
    cy.get('#dashMonthlyRevenue').should('have.attr', 'title').and('match', /\.\d{2}$/)   // money: the paisa in the tooltip
  })

  it('"On Terms" shows a number — never the loading placeholder', () => {
    openDashboard()
    cy.get('[data-widget="installmentsDue"]').should('be.visible')
    cy.get('#dashInstallmentsDue').should(($v) => expect($v.text().trim()).to.match(/^\d[\d,. ]*$/))
  })

  it('a tile still drills into its list, and the capability ordering still leads with "On Terms"', () => {
    openDashboard()
    cy.window().then((w) => {
      const first = tiles(w)[0].closest('[data-widget]').getAttribute('data-widget')
      expect(first, 'dashboard-widgets.js still promotes the capability tile').to.eq('installmentsDue')
    })
    cy.get('[data-widget="companies"] .kpi-card').click()
    cy.get('#CompanyDiv').should('be.visible')
  })
})
